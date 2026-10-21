package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.CashQuery;
import com.yumi.finance.cash.api.RefundAfterSalesPaymentCommand;
import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesCaseView;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeRoute;
import com.yumi.orders.aftersales.api.ChangeAfterSalesObligationCommand;
import com.yumi.orders.aftersales.api.AfterSalesQuery;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.AfterSalesRouteService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.DisposeReturnedProductCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.aftersales.api.RequestRepairCommand;
import com.yumi.orders.aftersales.application.AfterSalesReturnDispositionServiceImpl;
import com.yumi.orders.aftersales.application.port.AfterSalesRepository;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.PreparedReturnDisposition;
import com.yumi.production.api.PreparedReturnReentry;
import com.yumi.production.api.ProductionAfterSalesReturnParticipant;
import com.yumi.production.api.RegisterReturnCommand;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionCommand;
import com.yumi.production.api.ReturnDispositionResult;
import com.yumi.production.api.ReturnDispositionType;
import com.yumi.production.api.ReturnReentryResult;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 售后财务闭环：最近交付退回、修复+报废转线下、仅缺口补发与售后现金。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §13、`docs/一期/13-财务结算模块.md` §8.2。
 * 真实 MySQL 验证 10/3/7 守恒、唯一退出、移交不可逆、错误义务引用与双端回滚。
 */
class AfterSalesFinanceFlowIT extends AbstractOrderFixtureTest {

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesReturnService afterSalesReturns;

    @Autowired
    private AfterSalesRouteService afterSalesRoutes;

    @Autowired
    private AfterSalesQuery afterSalesQuery;

    @Autowired
    private CashApplicationService cash;

    @Autowired
    private CashQuery cashQuery;

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
    @DisplayName("10 件退回按 3 修复 + 7 报废转线下守恒，仅缺口补发且售后赔付独立入现金")
    void returnRepairScrapReplacementAndCash() {
        OrderRef order = completedOrder(10);
        String itemId = onlyItemId(order);
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("FLOW-AS-OPEN"), order.orderBusinessId(), "部分破损",
                AfterSalesRouteType.MIXED,
                List.of(
                        new AfterSalesObligationRequest(
                                AfterSalesObligationType.RETURN_AND_REPAIR, 10, null,
                                LocalDate.parse("2026-10-20"), "退回修复", itemId),
                        new AfterSalesObligationRequest(
                                AfterSalesObligationType.REFUND, null,
                                SettlementMoney.of(new BigDecimal("50.00"), CurrencyCode.CNY),
                                LocalDate.parse("2026-10-20"), "赔付", null)))));
        AfterSalesCaseView caseView = asAdmin(
                () -> afterSalesQuery.get(caseRef.caseBusinessId()));
        String repairObligationId = obligationOf(caseView, AfterSalesObligationType.RETURN_AND_REPAIR);
        String refundObligationId = obligationOf(caseView, AfterSalesObligationType.REFUND);

        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(
                        uniqueKey("FLOW-AS-RETURN"), repairObligationId, 10, "UNREPAIRED")))
                .receiptItemBusinessId();
        String returnedSourceId = returnedSourceId(receiptItemId);
        assertThat(poolQuantity(returnedSourceId, "available")).isEqualTo(10);

        asAdmin(() -> afterSalesRoutes.requestRepair(new RequestRepairCommand(
                uniqueKey("FLOW-REPAIR"), repairObligationId, receiptItemId, 3)));
        asAdmin(() -> afterSales.disposeReturnedProduct(scrapOffline(receiptItemId, 7)));

        assertThat(poolQuantity(returnedSourceId, "available")).isZero();
        assertThat(poolQuantity(returnedSourceId, "disposed")).isEqualTo(7);
        assertThat(count("production_terminal_exits")).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT quantity FROM production_terminal_exits LIMIT 1
                        """).query(Integer.class).single()).isEqualTo(7);
        assertThat(orderJdbc.sql("""
                        SELECT physical_destination FROM production_terminal_exits LIMIT 1
                        """).query(String.class).single()).isEqualTo("TRANSFERRED_OFFLINE_INVENTORY");

        assertThat(demandQuantity(order.orderBusinessId(), "NEW_PRODUCT")).isEqualTo(10);
        assertThat(demandQuantity(order.orderBusinessId(), "RETURNED_PRODUCT_REPAIR")).isEqualTo(3);
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_sources
                        WHERE demand_business_id IN (
                            SELECT demand_business_id FROM production_demands
                            WHERE order_business_id = :id AND demand_type = 'RETURNED_PRODUCT_REPAIR')
                        """).param("id", order.orderBusinessId()).query(Long.class).single())
                .isEqualTo(1);

        asAdmin(() -> cash.refundAfterSales(new RefundAfterSalesPaymentCommand(
                uniqueKey("FLOW-AS-CASH"), refundObligationId,
                SettlementMoney.of(new BigDecimal("50.00"), CurrencyCode.CNY),
                LocalDate.parse("2026-10-20"))));
        CashQuery.SourceCashSummary summary = asAdmin(() -> cashQuery.getSourceSummary(
                "orders", "AFTER_SALES_OBLIGATION", refundObligationId));
        assertThat(summary.paidAmount()).isEqualByComparingTo("50.00");
        assertThat(cashQuery).isNotNull();

        assertThatThrownBy(() -> asAdmin(() -> cash.refundAfterSales(
                new RefundAfterSalesPaymentCommand(
                        uniqueKey("FLOW-AS-WRONG"), "AOB-NOT-EXIST",
                        SettlementMoney.of(new BigDecimal("50.00"), CurrencyCode.CNY),
                        LocalDate.parse("2026-10-20")))))
                .isInstanceOf(ApiException.class);
        assertThat(count("finance_cash_facts")).isEqualTo(1);
    }

    @Test
    @DisplayName("修复失败只按当前缺口转补发，不重复制作已修复数量")
    void replacementOnlyCoversRepairGap() {
        OrderRef order = completedOrder(5);
        String itemId = onlyItemId(order);
        String employeeId = orderJdbc.sql("""
                        SELECT e.subject_business_id FROM production_execution_facts e
                        JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                        WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("FLOW-AS-OPEN3"), order.orderBusinessId(), "修复失败转补发",
                AfterSalesRouteType.RETURN_AND_REWORK,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.RETURN_AND_REPAIR, 5, null,
                        LocalDate.parse("2026-10-20"), "退回修复", itemId)))));
        AfterSalesCaseView caseView = asAdmin(
                () -> afterSalesQuery.get(caseRef.caseBusinessId()));
        String obligationId = obligationOf(caseView, AfterSalesObligationType.RETURN_AND_REPAIR);
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(
                        uniqueKey("FLOW-AS-RETURN3"), obligationId, 5, "UNREPAIRED")))
                .receiptItemBusinessId();

        var repair = asAdmin(() -> afterSalesRoutes.requestRepair(new RequestRepairCommand(
                uniqueKey("FLOW-REPAIR3"), obligationId, receiptItemId, 5)));
        var task = asAdmin(() -> orderTaskService.arrange(
                new com.yumi.production.api.ArrangeTaskCommand(
                        com.yumi.production.api.TaskType.REPAIR,
                        new com.yumi.production.api.SubjectRef(
                                com.yumi.production.api.SubjectType.EMPLOYEE, employeeId),
                        LocalDate.parse("2026-10-06"),
                        com.yumi.production.api.TargetProcess.MAKING,
                        List.of(new com.yumi.production.api.TaskItemRequest(
                                repair.productionSourceBusinessId(), 5)),
                        null, null, null)));
        String taskItemId = asAdmin(() -> orderTaskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> orderVerificationService.verify(
                new com.yumi.production.api.VerifyTaskCommand(List.of(
                        new com.yumi.production.api.TaskItemResultRequest(
                                taskItemId, 2, 0, 0, 3,
                                List.of(new com.yumi.production.api.DefectGroupRequest(
                                        "OTHER", "修复失败", 3, 0,
                                        List.of(new com.yumi.production.api.DefectGroupRequest
                                                .ScrapPartRequest(
                                                3, PhysicalDestination.DESTROYED,
                                                ResponsibilityType.WAIVED, null, "修复失败报废")))),
                                null)))));

        asAdmin(() -> afterSalesRoutes.changeObligation(
                new ChangeAfterSalesObligationCommand(
                        uniqueKey("FLOW-REPLACE3"), obligationId, 3,
                        AfterSalesObligationChangeRoute.REPLACEMENT, null, "修复失败转补发")));

        assertThat(demandQuantity(order.orderBusinessId(), "RETURNED_PRODUCT_REPAIR")).isEqualTo(2);
        assertThat(demandQuantity(order.orderBusinessId(), "NEW_PRODUCT")).isEqualTo(8);
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM after_sales_obligations
                        WHERE case_business_id = :id
                        """).param("id", caseRef.caseBusinessId()).query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("生产侧处置提交失败时售后处置、责任与退出全部回滚，池数量不变")
    void productionWriteFailureRollsBackBothSides() {
        OrderRef order = completedOrder(2);
        String itemId = onlyItemId(order);
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("FLOW-AS-OPEN2"), order.orderBusinessId(), "池处置回滚",
                AfterSalesRouteType.RETURN_AND_REWORK,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.RETURN_AND_REPAIR, 2, null,
                        LocalDate.parse("2026-10-20"), "退回", itemId)))));
        AfterSalesCaseView caseView = asAdmin(
                () -> afterSalesQuery.get(caseRef.caseBusinessId()));
        String obligationId = obligationOf(caseView, AfterSalesObligationType.RETURN_AND_REPAIR);
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(
                        uniqueKey("FLOW-AS-RETURN2"), obligationId, 2, "UNREPAIRED")))
                .receiptItemBusinessId();
        String returnedSourceId = returnedSourceId(receiptItemId);

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
                scrapOffline(receiptItemId, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注入");
        assertThat(count("after_sales_return_disposition_facts")).isZero();
        assertThat(count("production_returned_product_disposition_facts")).isZero();
        assertThat(count("production_terminal_exits")).isZero();
        assertThat(poolQuantity(returnedSourceId, "available")).isEqualTo(2);
        assertThat(poolQuantity(returnedSourceId, "disposed")).isZero();
    }

    private String obligationOf(AfterSalesCaseView view, AfterSalesObligationType type) {
        return view.obligations().stream()
                .filter(obligation -> obligation.obligationType() == type)
                .findFirst().orElseThrow().obligationBusinessId();
    }

    private String onlyItemId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String returnedSourceId(String receiptItemId) {
        return orderJdbc.sql("""
                        SELECT production_returned_source_business_id
                        FROM after_sales_return_receipt_items WHERE receipt_item_business_id = :id
                        """).param("id", receiptItemId).query(String.class).single();
    }

    private int poolQuantity(String returnedSourceId, String column) {
        return orderJdbc.sql("SELECT " + column + "_quantity FROM production_returned_product_sources"
                        + " WHERE returned_source_business_id = :id")
                .param("id", returnedSourceId).query(Integer.class).single();
    }

    private int demandQuantity(String orderId, String type) {
        return orderJdbc.sql("""
                        SELECT COALESCE(SUM(required_quantity), 0) FROM production_demands
                        WHERE order_business_id = :id AND demand_type = :type
                        """).param("id", orderId).param("type", type)
                .query(Integer.class).single();
    }

    private DisposeReturnedProductCommand scrapOffline(String receiptItemId, int quantity) {
        return new DisposeReturnedProductCommand(
                uniqueKey("FLOW-DISPOSE-SCRAP"), receiptItemId, quantity,
                ReturnDispositionType.SCRAPPED, PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                LocalDate.parse("2026-10-15"), "报废转线下",
                ResponsibilityType.STUDIO, "STUDIO", null,
                "售后线下库位", "报废移交", "张三", "李四",
                LocalDateTime.parse("2026-10-15T10:00:00"));
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
