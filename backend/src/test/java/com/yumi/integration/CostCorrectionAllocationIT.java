package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.costledger.api.AccrueDirectLaborCostsCommand;
import com.yumi.finance.costledger.api.AccrueProductionMaterialCostsCommand;
import com.yumi.finance.costledger.api.AdjustCostCommand;
import com.yumi.finance.costledger.api.AllocateCostCommand;
import com.yumi.finance.costledger.api.RetryCostFailureCommand;
import com.yumi.finance.costledger.application.CostLedgerApplicationServiceImpl;
import com.yumi.finance.costledger.application.CostLedgerRepository;
import com.yumi.finance.costledger.api.CostLedgerApplicationService;
import com.yumi.finance.costledger.api.CostLedgerQuery;
import com.yumi.finance.costledger.api.CostReportingFeed;
import com.yumi.finance.costledger.api.OperatingExpenseCommand;
import com.yumi.finance.costledger.api.OperatingExpenseService;
import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.ConfirmEmployeePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollConfirmationService;
import com.yumi.finance.payroll.api.PayrollCostSourceFeed;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.orders.aftersales.api.OrderAfterSalesCostSourceFeed;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.production.api.ProductionPayrollMaterialSourceFeed;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.support.RecordingAuthorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 成本冲正、显式分摊与失败恢复。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §6、§8.5。真实 MySQL 验证：
 * 冲正取原金额并追加替代，分摊合计守恒，失败重试不双计。
 */
class CostCorrectionAllocationIT extends AbstractOrderFixtureTest {

    @Autowired
    private CostLedgerApplicationService costLedger;

    @Autowired
    private CostLedgerRepository costLedgerRepository;

    @Autowired
    private ProductionPayrollMaterialSourceFeed materialFeed;

    @Autowired
    private PayrollCostSourceFeed payrollCostFeed;

    @Autowired
    private OrderAfterSalesCostSourceFeed afterSalesCostFeed;

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;

    @Autowired
    private PayrollConfirmationService confirmations;

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
    private OperatingExpenseService operatingExpenses;

    @Autowired
    private CostReportingFeed costFeed;

    @Autowired
    private CostLedgerQuery costQuery;

    @Test
    @DisplayName("成本冲正不取新价格，显式分摊合计守恒，失败重试不双计")
    void correctionAllocationAndRetry() {
        completedOrder(2);
        asAdmin(() -> costLedger.accrueProductionMaterialCosts(
                new AccrueProductionMaterialCostsCommand(
                        uniqueKey("COST-MAT"), LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"))));
        var original = costEntry("MATERIAL");
        BigDecimal originalAmount = entryAmount(original);
        BigDecimal newAmount = originalAmount.subtract(new BigDecimal("10.0000"));

        var adjusted = asAdmin(() -> costLedger.adjust(new AdjustCostCommand(
                uniqueKey("COST-ADJUST"), original, CostAmount.of(newAmount, CurrencyCode.CNY),
                "复核下调")));
        assertThat(adjusted.amount().value()).isEqualByComparingTo(newAmount);
        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_cost_entries
                        WHERE source_business_id = (
                            SELECT source_business_id FROM finance_cost_entries
                            WHERE cost_entry_business_id = :id)
                        """).param("id", original).query(BigDecimal.class).single())
                .isEqualByComparingTo(newAmount);
        assertThat(orderJdbc.sql("""
                        SELECT operation_type FROM finance_cost_entries
                        WHERE cost_entry_business_id = :id
                        """).param("id", adjusted.costEntryBusinessId()).query(String.class).single())
                .isEqualTo("ACCRUE");

        BigDecimal half = newAmount.divide(new BigDecimal("2.0000"));
        var allocated = asAdmin(() -> costLedger.allocate(new AllocateCostCommand(
                uniqueKey("COST-ALLOC"), adjusted.costEntryBusinessId(),
                List.of(
                        new AllocateCostCommand.AllocationRequest(
                                "ORDER", "ORD-A", CostAmount.of(half, CurrencyCode.CNY)),
                        new AllocateCostCommand.AllocationRequest(
                                "ORDER", "ORD-B",
                                CostAmount.of(newAmount.subtract(half), CurrencyCode.CNY))))));
        assertThat(allocated.allocated()).isTrue();
        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_cost_allocations
                        WHERE cost_entry_business_id = :id
                        """).param("id", adjusted.costEntryBusinessId()).query(BigDecimal.class).single())
                .isEqualByComparingTo(newAmount);

        var second = costEntry("MATERIAL", adjusted.costEntryBusinessId());
        assertThatThrownBy(() -> asAdmin(() -> costLedger.allocate(new AllocateCostCommand(
                uniqueKey("COST-ALLOC"), second,
                List.of(new AllocateCostCommand.AllocationRequest(
                        "ORDER", "ORD-A", CostAmount.of(BigDecimal.ONE, CurrencyCode.CNY)))))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("COST_ALLOCATION_MISMATCH"));

        // 人工成本未分摊时保持不完整状态
        confirmPayroll();
        asAdmin(() -> costLedger.accrueDirectLaborCosts(new AccrueDirectLaborCostsCommand(
                uniqueKey("COST-LABOR"), LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31"))));
        assertThat(orderJdbc.sql("""
                        SELECT allocated FROM finance_cost_entries
                        WHERE cost_category = 'DIRECT_LABOR'
                        """).query(Boolean.class).single())
                .isFalse();

        // 失败恢复：重试不新增成本事实
        String sourceId = orderJdbc.sql("""
                        SELECT source_business_id FROM finance_cost_entries
                        WHERE cost_entry_business_id = :id
                        """).param("id", second).query(String.class).single();
        orderJdbc.sql("""
                        INSERT INTO finance_cost_failures (
                            failure_business_id, source_module, source_type, source_business_id,
                            reason_text, status, attempts, last_error, created_at, updated_at)
                        VALUES ('CF-TEST', 'production', 'MATERIAL_SOURCE', :sourceId,
                                '注入失败', 'PENDING', 1, 'injected', UTC_TIMESTAMP(6),
                                UTC_TIMESTAMP(6))
                        """).param("sourceId", sourceId).update();
        long before = count("finance_cost_entries");
        asAdmin(() -> costLedger.retryFailure(new RetryCostFailureCommand(
                uniqueKey("COST-RETRY"), "CF-TEST",
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))));
        assertThat(count("finance_cost_entries")).isEqualTo(before);
        assertThat(orderJdbc.sql("""
                        SELECT status FROM finance_cost_failures WHERE failure_business_id = 'CF-TEST'
                        """).query(String.class).single()).isEqualTo("RESOLVED");

        // 经营费用与成本同事务：调用方回滚不留下任何一侧
        assertThatThrownBy(() -> asAdmin(() -> new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).execute(status -> {
            operatingExpenses.record(new OperatingExpenseCommand(
                    "OPE-TEST", "CASH-TEST",
                    CostAmount.of(new BigDecimal("50.0000"), CurrencyCode.CNY),
                    LocalDate.parse("2026-10-20"), "RENT"));
            throw new IllegalStateException("调用方现金保存失败");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(count("finance_operating_expenses")).isZero();
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_cost_entries
                        WHERE cost_category = 'OPERATING_EXPENSE'
                        """).query(Long.class).single()).isZero();

        // 成本 Feed 与主体汇总可读
        var feed = costFeed.changedSince(null, 500);
        assertThat(feed.items()).isNotEmpty();
        assertThat(costQuery.listPendingFailures()).isEmpty();
        assertThat(costQuery.getSubjectCost(
                "ORDER", "ORD-A", LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31")).subjectType()).isEqualTo("ORDER");

        // 权限拒绝
        var denied = new RecordingAuthorization();
        denied.deny(PermissionCodes.COST_ADJUST);
        var blocked = new CostLedgerApplicationServiceImpl(
                costLedgerRepository, materialFeed, payrollCostFeed, afterSalesCostFeed,
                idempotency, denied, currentActorProvider, clock, transactionManager);
        assertThatThrownBy(() -> asAdmin(() -> blocked.adjust(new AdjustCostCommand(
                uniqueKey("COST-ADJUST"), second, CostAmount.of(BigDecimal.ONE, CurrencyCode.CNY),
                "无权限"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PERMISSION_DENIED"));
    }

    private void confirmPayroll() {
        asAdmin(() -> payrollIngestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST"))));
        PayrollPeriodRef period = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAYROLL-PERIOD"), "2026-10",
                        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))));
        asAdmin(() -> payroll.calculate(
                new CalculatePayrollCommand(uniqueKey("PAYROLL-CALC"), period.periodBusinessId())));
        String worker = orderJdbc.sql("""
                        SELECT e.subject_business_id FROM production_execution_facts e
                        JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                        WHERE e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).query(String.class).single();
        EmployeePayrollView view = asAdmin(() -> payrollQuery.getEmployeePayroll(
                period.periodBusinessId(), worker));
        asAdmin(() -> confirmations.confirmEmployee(new ConfirmEmployeePayrollCommand(
                uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(), view.rowVersion(),
                view.referenceFingerprint(),
                SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY), "核定",
                List.of())));
    }

    private String costEntry(String category) {
        return costEntry(category, null);
    }

    private String costEntry(String category, String excludeEntryId) {
        return orderJdbc.sql("""
                        SELECT cost_entry_business_id FROM finance_cost_entries
                        WHERE cost_category = :category AND operation_type = 'ACCRUE'
                          AND (:excludeId IS NULL OR cost_entry_business_id <> :excludeId)
                        ORDER BY amount DESC, cost_entry_business_id LIMIT 1
                        """).param("category", category).param("excludeId", excludeEntryId)
                .query(String.class).single();
    }

    private BigDecimal entryAmount(String entryId) {
        return orderJdbc.sql("""
                        SELECT amount FROM finance_cost_entries WHERE cost_entry_business_id = :id
                        """).param("id", entryId).query(BigDecimal.class).single();
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
