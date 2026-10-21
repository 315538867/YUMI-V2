package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.finance.costledger.api.AccrueAfterSalesCostsCommand;
import com.yumi.finance.costledger.api.AccrueDirectLaborCostsCommand;
import com.yumi.finance.costledger.api.CostLedgerApplicationService;
import com.yumi.finance.payroll.api.AdjustPayrollCommand;
import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.ConfirmEmployeePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.finance.payroll.api.PayrollAdjustmentService;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollConfirmationService;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesCostService;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.ConfirmAfterSalesCostCommand;
import com.yumi.orders.aftersales.api.CorrectAfterSalesCostCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.SignedSettlementAmount;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 人工与售后成本归集：只取实际核定人工，售后来源按更正追加反向。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §6、§8.5。真实 MySQL 验证：
 * 处理明细不加减人工成本；售后更正产生冲正与替代；重复归集不入账。
 */
class LaborAndAfterSalesCostIT extends AbstractOrderFixtureTest {

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;

    @Autowired
    private PayrollConfirmationService confirmations;

    @Autowired
    private PayrollAdjustmentService adjustments;

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private AfterSalesCostService afterSalesCosts;

    @Autowired
    private CostLedgerApplicationService costLedger;

    @Test
    @DisplayName("人工只取实际核定与调整，处理明细不加减成本；售后更正追加冲正")
    void laborAndAfterSalesCostsFollowConfirmedFacts() {
        OrderRef order = completedOrder(2);
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
                        WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        EmployeePayrollView view = asAdmin(() -> payrollQuery.getEmployeePayroll(
                period.periodBusinessId(), worker));
        String laborLine = view.lines().stream()
                .filter(line -> line.lineType().equals("LABOR"))
                .findFirst().orElseThrow().lineBusinessId();
        asAdmin(() -> confirmations.confirmEmployee(new ConfirmEmployeePayrollCommand(
                uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(), view.rowVersion(),
                view.referenceFingerprint(),
                SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY), "核定",
                List.of(new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                        laborLine,
                        SettlementMoney.of(new BigDecimal("30.00"), CurrencyCode.CNY),
                        "处理项不抵扣")))));
        asAdmin(() -> adjustments.adjust(new AdjustPayrollCommand(
                uniqueKey("PAYROLL-ADJUST"), view.resultBusinessId(),
                SignedSettlementAmount.of(new BigDecimal("-100.00")), "差额调整", null)));

        var laborRun = asAdmin(() -> costLedger.accrueDirectLaborCosts(
                new AccrueDirectLaborCostsCommand(
                        uniqueKey("COST-LABOR"), LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"))));
        assertThat(laborRun.accruedCount()).isEqualTo(2);
        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_cost_entries
                        WHERE cost_category = 'DIRECT_LABOR'
                        """).query(BigDecimal.class).single())
                .isEqualByComparingTo("900.0000");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_cost_entries
                        WHERE cost_category = 'DIRECT_LABOR'
                        """).query(Long.class).single()).isEqualTo(2);

        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("AS-OPEN"), order.orderBusinessId(), "售后费用",
                AfterSalesRouteType.COMPENSATION_ONLY,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.COMPENSATION, null,
                        SettlementMoney.of(new BigDecimal("1.00"), CurrencyCode.CNY),
                        LocalDate.parse("2026-10-20"), "赔付", null)))));
        String costSource = asAdmin(() -> afterSalesCosts.confirmCost(
                new ConfirmAfterSalesCostCommand(
                        uniqueKey("AS-COST"), caseRef.caseBusinessId(), "LABOR",
                        SettlementMoney.of(new BigDecimal("80.00"), CurrencyCode.CNY),
                        LocalDate.parse("2026-10-15"), "返修人工"))).sourceBusinessId();
        asAdmin(() -> afterSalesCosts.correctCost(new CorrectAfterSalesCostCommand(
                uniqueKey("AS-COST"), caseRef.caseBusinessId(), costSource,
                SettlementMoney.of(new BigDecimal("50.00"), CurrencyCode.CNY),
                LocalDate.parse("2026-10-16"), "复核下调")));

        var afterSalesRun = asAdmin(() -> costLedger.accrueAfterSalesCosts(
                new AccrueAfterSalesCostsCommand(uniqueKey("COST-AS"))));
        assertThat(afterSalesRun.accruedCount()).isEqualTo(3);
        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_cost_entries
                        WHERE cost_category = 'AFTER_SALES'
                        """).query(BigDecimal.class).single())
                .isEqualByComparingTo("50.0000");

        // 重复归集不入账，且工资核定事实仍在
        var laborReplay = asAdmin(() -> costLedger.accrueDirectLaborCosts(
                new AccrueDirectLaborCostsCommand(
                        uniqueKey("COST-LABOR"), LocalDate.parse("2026-10-01"),
                        LocalDate.parse("2026-10-31"))));
        var afterSalesReplay = asAdmin(() -> costLedger.accrueAfterSalesCosts(
                new AccrueAfterSalesCostsCommand(uniqueKey("COST-AS"))));
        assertThat(laborReplay.accruedCount()).isZero();
        assertThat(afterSalesReplay.accruedCount()).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM finance_payroll_confirmations")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM finance_cost_entries")
                .query(Long.class).single()).isEqualTo(5);
    }
}
