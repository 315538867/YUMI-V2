package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.DisposeReturnedProductCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.aftersales.api.ReturnDispositionRef;
import com.yumi.orders.aftersales.application.AfterSalesReturnDispositionServiceImpl;
import com.yumi.orders.aftersales.application.port.AfterSalesRepository;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.PreparedReturnDisposition;
import com.yumi.production.api.PreparedReturnReentry;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionAfterSalesReturnParticipant;
import com.yumi.production.api.RegisterReturnCommand;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionCommand;
import com.yumi.production.api.ReturnDispositionResult;
import com.yumi.production.api.ReturnDispositionType;
import com.yumi.production.api.ReturnReentryResult;
import com.yumi.support.AbstractOrderFixtureTest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 售后池处置的幂等、并发与双端回滚。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §13.2、§14。真实 MySQL 验证：
 * orders 校验案例、义务、接收明细和数量；任一写入失败全部回滚；重试不重复处置。
 */
class AfterSalesReturnDispositionIT extends AbstractOrderFixtureTest {

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesReturnService afterSalesReturns;

    @Autowired
    private AfterSalesRepository afterSalesRepository;

    @Autowired
    private ProductionAfterSalesReturnParticipant productionReturns;

    @Autowired
    private IdempotentCommandService idempotency;

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private CurrentActorProvider currentActorProvider;

    @Autowired
    private AuditService audit;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("同一处置命令重试复用原结果，只产生一份处置、责任与退出")
    void disposalIsIdempotent() {
        PoolContext pool = returnedPool(3, 2);
        DisposeReturnedProductCommand command = scrap(pool.receiptItemId(), 1);

        ReturnDispositionRef first = asAdmin(() -> afterSales.disposeReturnedProduct(command));
        ReturnDispositionRef second = asAdmin(() -> afterSales.disposeReturnedProduct(command));

        assertThat(second.dispositionFactBusinessId()).isEqualTo(first.dispositionFactBusinessId());
        assertThat(count("after_sales_return_disposition_facts")).isEqualTo(1);
        assertThat(count("production_returned_product_disposition_facts")).isEqualTo(1);
        assertThat(count("production_terminal_exits")).isEqualTo(1);
        assertThat(poolQuantity(pool.returnedSourceId(), "disposed")).isEqualTo(1);
    }

    @Test
    @DisplayName("并发处置同一池数量只有一笔成功，不超可用")
    void concurrentDisposalCannotExceedPool() throws Exception {
        PoolContext pool = returnedPool(4, 3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = attempt(start, scrap(pool.receiptItemId(), 2));
            Callable<Object> second = attempt(start, scrap(pool.receiptItemId(), 2));
            Future<Object> firstResult = executor.submit(first);
            Future<Object> secondResult = executor.submit(second);
            start.countDown();
            List<Object> results = List.of(
                    firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));

            assertThat(results.stream().filter(ReturnDispositionRef.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ApiException.class::isInstance)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("after_sales_return_disposition_facts")).isEqualTo(1);
        assertThat(count("production_returned_product_disposition_facts")).isEqualTo(1);
        assertThat(poolQuantity(pool.returnedSourceId(), "disposed")).isEqualTo(2);
        assertThat(poolQuantity(pool.returnedSourceId(), "available")).isEqualTo(1);
    }

    @Test
    @DisplayName("orders 处置记录写入失败时生产侧处置与退出全部回滚")
    void ordersWriteFailureRollsBackProduction() {
        PoolContext pool = returnedPool(2, 2);
        var failingRepository = failingRepository("insertDisposition");
        var service = new AfterSalesReturnDispositionServiceImpl(
                failingRepository, productionReturns, idempotency, authorization,
                currentActorProvider, audit, clock, transactionManager);

        assertThatThrownBy(() -> asAdmin(() -> service.disposeReturnedProduct(
                scrap(pool.receiptItemId(), 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注入");
        assertThat(count("after_sales_return_disposition_facts")).isZero();
        assertThat(count("production_returned_product_disposition_facts")).isZero();
        assertThat(count("production_terminal_exits")).isZero();
        assertThat(poolQuantity(pool.returnedSourceId(), "available")).isEqualTo(2);
        assertThat(poolQuantity(pool.returnedSourceId(), "disposed")).isZero();
    }

    @Test
    @DisplayName("生产处置提交失败时 orders 处置记录与池数量全部回滚")
    void productionWriteFailureRollsBackOrders() {
        PoolContext pool = returnedPool(2, 2);
        var failingProduction = new ProductionAfterSalesReturnParticipant() {
            @Override
            public PreparedReturnReentry prepareRegisterReturn(RegisterReturnCommand command) {
                return productionReturns.prepareRegisterReturn(command);
            }

            @Override
            public ReturnReentryResult commitRegisterReturn(PreparedReturnReentry prepared) {
                return productionReturns.commitRegisterReturn(prepared);
            }

            @Override
            public PreparedReturnDisposition prepareDisposition(ReturnDispositionCommand command) {
                return productionReturns.prepareDisposition(command);
            }

            @Override
            public int availablePoolQuantity(String returnedSourceBusinessId) {
                return productionReturns.availablePoolQuantity(returnedSourceBusinessId);
            }

            @Override
            public ReturnDispositionResult commitDisposition(
                    ReturnDispositionCommand command, PreparedReturnDisposition prepared) {
                throw new IllegalStateException("注入生产处置写入失败");
            }
        };
        var service = new AfterSalesReturnDispositionServiceImpl(
                afterSalesRepository, failingProduction, idempotency, authorization,
                currentActorProvider, audit, clock, transactionManager);

        assertThatThrownBy(() -> asAdmin(() -> service.disposeReturnedProduct(
                scrap(pool.receiptItemId(), 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注入");
        assertThat(count("after_sales_return_disposition_facts")).isZero();
        assertThat(count("production_returned_product_disposition_facts")).isZero();
        assertThat(count("production_terminal_exits")).isZero();
        assertThat(poolQuantity(pool.returnedSourceId(), "available")).isEqualTo(2);
    }

    private DisposeReturnedProductCommand scrap(String receiptItemId, int quantity) {
        return new DisposeReturnedProductCommand(
                uniqueKey("AS-DISPOSE"), receiptItemId, quantity,
                ReturnDispositionType.SCRAPPED, PhysicalDestination.DESTROYED,
                LocalDate.parse("2026-10-15"), "池内报废",
                ResponsibilityType.WAIVED, null, "客户同意报废",
                null, null, null, null, null);
    }

    private Callable<Object> attempt(CountDownLatch start, DisposeReturnedProductCommand command) {
        return () -> {
            start.await();
            try {
                return asAdmin(() -> afterSales.disposeReturnedProduct(command));
            } catch (ApiException exception) {
                return exception;
            }
        };
    }

    private AfterSalesRepository failingRepository(String methodName) {
        return (AfterSalesRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AfterSalesRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals(methodName)) {
                        throw new IllegalStateException("注入 orders 写入失败：" + methodName);
                    }
                    try {
                        return method.invoke(afterSalesRepository, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private PoolContext returnedPool(int orderQuantity, int returnQuantity) {
        OrderRef order = completedOrder(orderQuantity);
        String itemId = onlyItemId(order);
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("AS-OPEN"), order.orderBusinessId(), "缝边开裂",
                AfterSalesRouteType.RETURN_AND_REWORK,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.RETURN_AND_REPAIR, returnQuantity, null,
                        LocalDate.parse("2026-10-20"), "返修", itemId)))));
        String obligationId = orderJdbc.sql("""
                        SELECT obligation_business_id FROM after_sales_obligations
                        WHERE case_business_id = :id
                        """).param("id", caseRef.caseBusinessId()).query(String.class).single();
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(
                        uniqueKey("AS-RECEIVE"), obligationId, returnQuantity, "UNREPAIRED")))
                .receiptItemBusinessId();
        String returnedSourceId = orderJdbc.sql("""
                        SELECT production_returned_source_business_id
                        FROM after_sales_return_receipt_items WHERE receipt_item_business_id = :id
                        """).param("id", receiptItemId).query(String.class).single();
        return new PoolContext(order, receiptItemId, returnedSourceId);
    }

    private String onlyItemId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private int poolQuantity(String returnedSourceId, String column) {
        return orderJdbc.sql("SELECT " + column + "_quantity FROM production_returned_product_sources"
                        + " WHERE returned_source_business_id = :id")
                .param("id", returnedSourceId).query(Integer.class).single();
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private record PoolContext(OrderRef order, String receiptItemId, String returnedSourceId) {
    }
}
