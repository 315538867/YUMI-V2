package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.finance.payroll.application.PayrollSourceIngestionServiceImpl;
import com.yumi.finance.payroll.application.PayrollSourceRepository;
import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.DisposeReturnedProductCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ProductionPayrollSourceFeed;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionType;
import com.yumi.support.AbstractOrderFixtureTest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 工资来源摄取：独立游标、幂等重放、失败不越游标。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §8.5、§10。真实 MySQL 验证：
 * 同一来源重复读取不重复入库，失败页不推进游标，月守卫原子递增。
 */
class PayrollSourceIngestionIT extends AbstractOrderFixtureTest {

    @Autowired
    private PayrollSourceIngestionService ingestion;

    @Autowired
    private PayrollSourceRepository payrollSources;

    @Autowired
    private ProductionPayrollSourceFeed productionFeed;

    @Autowired
    private IdempotentCommandService idempotency;

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private CurrentActorProvider currentActorProvider;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesReturnService afterSalesReturns;

    @Test
    @DisplayName("摄取劳动来源推进游标，重复命令不重复入库")
    void laborIngestionIsIdempotent() {
        OrderRef order = completedOrder(2);
        String worker = orderJdbc.sql("""
                        SELECT e.subject_business_id FROM production_execution_facts e
                        JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                        WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).param("id", order.orderBusinessId()).query(String.class).single();

        var first = asAdmin(() -> ingestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST"))));
        assertThat(first.laborItems()).isPositive();
        long laborEntries = count("finance_payroll_labor_entries");
        assertThat(laborEntries).isPositive();
        assertThat(count("finance_payroll_responsibility_entries")).isZero();
        assertThat(monthGuardVersion(worker)).isPositive();

        var replay = asAdmin(() -> ingestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST"))));
        assertThat(replay.laborItems()).isZero();
        assertThat(count("finance_payroll_labor_entries")).isEqualTo(laborEntries);
        assertThat(orderJdbc.sql("""
                        SELECT cursor_source_id FROM finance_payroll_source_cursors
                        WHERE feed_code = 'LABOR'
                        """).query(String.class).single()).isNotBlank();
    }

    @Test
    @DisplayName("摄取失败时整页回滚，游标不前进")
    void failedIngestionDoesNotAdvanceCursor() {
        completedOrder(2);
        assertThat(count("production_labor_sources")).isPositive();
        assertThat(count("finance_payroll_source_cursors")).isZero();
        var failing = failingRepository("insertLaborEntry");
        var service = new PayrollSourceIngestionServiceImpl(
                failing, productionFeed, idempotency, authorization, currentActorProvider,
                clock, transactionManager);

        assertThatThrownBy(() -> asAdmin(() -> service.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注入");
        assertThat(count("finance_payroll_labor_entries")).isZero();
        assertThat(count("finance_payroll_source_cursors")).isZero();
        assertThat(count("finance_payroll_month_guards")).isZero();
    }

    @Test
    @DisplayName("退回池责任发布为独立责任来源，供工资参考追溯")
    void poolResponsibilityIsIngested() {
        OrderRef order = completedOrder(2);
        String itemId = orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("AS-OPEN"), order.orderBusinessId(), "缝边开裂",
                AfterSalesRouteType.RETURN_AND_REWORK,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.RETURN_AND_REPAIR, 1, null,
                        LocalDate.parse("2026-10-20"), "返修", itemId)))));
        String obligationId = orderJdbc.sql("""
                        SELECT obligation_business_id FROM after_sales_obligations
                        WHERE case_business_id = :id
                        """).param("id", caseRef.caseBusinessId()).query(String.class).single();
        String receiptItemId = asAdmin(() -> afterSalesReturns.receiveReturn(
                new ReceiveReturnCommand(uniqueKey("AS-RECEIVE"), obligationId, 1, "UNREPAIRED")))
                .receiptItemBusinessId();
        asAdmin(() -> afterSales.disposeReturnedProduct(new DisposeReturnedProductCommand(
                uniqueKey("AS-DISPOSE"), receiptItemId, 1,
                ReturnDispositionType.SCRAPPED, PhysicalDestination.DESTROYED,
                LocalDate.parse("2026-10-15"), "池内报废",
                ResponsibilityType.WAIVED, null, "报废免责",
                null, null, null, null, null)));

        var result = asAdmin(() -> ingestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST"))));
        assertThat(result.responsibilityItems()).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT effect_type FROM finance_payroll_responsibility_entries
                        """).query(String.class).single()).isEqualTo("APPLY");
    }

    private PayrollSourceRepository failingRepository(String methodName) {
        return (PayrollSourceRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PayrollSourceRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals(methodName)) {
                        throw new IllegalStateException("注入来源写入失败：" + methodName);
                    }
                    try {
                        return method.invoke(payrollSources, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private long monthGuardVersion(String employeeId) {
        return orderJdbc.sql("""
                        SELECT source_version FROM finance_payroll_month_guards
                        WHERE employee_business_id = :id
                        """).param("id", employeeId).query(Long.class).single();
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
