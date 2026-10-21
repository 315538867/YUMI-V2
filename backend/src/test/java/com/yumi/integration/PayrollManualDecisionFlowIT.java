package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.PayPayrollCommand;
import com.yumi.finance.cash.api.ReturnPayrollPaymentCommand;
import com.yumi.finance.payroll.api.AdjustPayrollCommand;
import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.ConfirmEmployeePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.GlueMonthReferenceView;
import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.finance.payroll.api.PayrollAdjustmentService;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollConfirmationService;
import com.yumi.finance.payroll.api.PayrollGlueAllowancePolicyService;
import com.yumi.finance.payroll.api.PayrollGlueAllowanceQuery;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollReviewRef;
import com.yumi.finance.payroll.api.PayrollReviewService;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.finance.payroll.api.PublishGlueAllowancePolicyCommand;
import com.yumi.finance.payroll.api.ReviewPayrollCommand;
import com.yumi.finance.payroll.api.RunGlueMonthReferenceCommand;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.SignedSettlementAmount;
import com.yumi.platform.api.value.WeightGram;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 逐人工资人工决策：自然月多期间、空/零处理项、独立核定、只读复核、跨月部分退回与调整。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §5、§8.3。真实 MySQL 验证：
 * 参考与处理金额不改变实际工资；复核不改应付；退回与调整分别只影响现金和应付。
 */
class PayrollManualDecisionFlowIT extends AbstractOrderFixtureTest {

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;

    @Autowired
    private PayrollConfirmationService confirmations;

    @Autowired
    private PayrollReviewService reviews;

    @Autowired
    private PayrollAdjustmentService adjustments;

    @Autowired
    private PayrollGlueAllowancePolicyService gluePolicies;

    @Autowired
    private PayrollGlueAllowanceQuery glueQueries;

    @Autowired
    private CashApplicationService cash;

    @Test
    @DisplayName("同月两期间、空/零处理项、只读复核、跨月部分退回后人工调整不产生现金")
    void manualDecisionFlow() {
        OrderRef order = completedOrder(1);
        String employeeId = orderJdbc.sql("""
                        SELECT e.subject_business_id FROM production_execution_facts e
                        JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                        WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).param("id", order.orderBusinessId()).query(String.class).single();

        asAdmin(() -> payrollIngestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAY-INGEST"))));
        PayrollPeriodRef firstHalf = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAY-PERIOD-A"), "2026-10A",
                        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-15"))));
        PayrollPeriodRef secondHalf = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAY-PERIOD-B"), "2026-10B",
                        LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-31"))));
        var firstCalculation = asAdmin(() -> payroll.calculate(
                new CalculatePayrollCommand(uniqueKey("PAY-CALC-A"), firstHalf.periodBusinessId())));
        var secondCalculation = asAdmin(() -> payroll.calculate(
                new CalculatePayrollCommand(uniqueKey("PAY-CALC-B"), secondHalf.periodBusinessId())));
        assertThat(firstCalculation.employeeCount()).isEqualTo(1);
        assertThat(secondCalculation.employeeCount()).isZero();

        EmployeePayrollView view = asAdmin(() -> payrollQuery.getEmployeePayroll(
                firstHalf.periodBusinessId(), employeeId));
        String firstLine = view.lines().getFirst().lineBusinessId();
        String secondLine = view.lines().size() > 1
                ? view.lines().get(1).lineBusinessId() : firstLine;
        asAdmin(() -> confirmations.confirmEmployee(new ConfirmEmployeePayrollCommand(
                uniqueKey("PAY-CONFIRM"), view.resultBusinessId(), view.rowVersion(),
                view.referenceFingerprint(),
                SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY),
                "人工核定 1000 元",
                List.of(
                        new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                firstLine, null, "留空"),
                        new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                secondLine, SettlementMoney.of(
                                        BigDecimal.ZERO, CurrencyCode.CNY), "明确为零")))));

        assertThat(payableOf(view.resultBusinessId())).isEqualByComparingTo("1000.00");
        assertThat(processingItemCount(view.resultBusinessId())).isEqualTo(2);
        assertThat(costEntriesOf(view.resultBusinessId())).isEqualTo(1);

        // 迟到责任：确认后再产生员工报废责任，复核应看到新参考行，但原核定和应付不变
        var auxProduct = createProduct("迟到责任商品-" + java.util.UUID.randomUUID(), 20, 10);
        String auxItem = "AUX-" + java.util.UUID.randomUUID();
        var auxDemand = createNewProductDemand(
                auxItem, com.yumi.production.api.TargetProcess.MAKING, 1, auxProduct, 4);
        var lateTask = asAdmin(() -> orderTaskService.arrange(
                new com.yumi.production.api.ArrangeTaskCommand(
                        com.yumi.production.api.TaskType.NORMAL_PRODUCTION,
                        new com.yumi.production.api.SubjectRef(
                                com.yumi.production.api.SubjectType.EMPLOYEE, employeeId),
                        LocalDate.parse("2026-10-07"),
                        com.yumi.production.api.TargetProcess.MAKING,
                        List.of(new com.yumi.production.api.TaskItemRequest(
                                auxDemand.productionSourceBusinessId(), 1)),
                        null, null, null)));
        String lateItemId = asAdmin(() -> orderTaskService.getTask(lateTask.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> orderVerificationService.verify(
                new com.yumi.production.api.VerifyTaskCommand(List.of(
                        new com.yumi.production.api.TaskItemResultRequest(
                                lateItemId, 0, 0, 0, 1,
                                List.of(new com.yumi.production.api.DefectGroupRequest(
                                        "OTHER", "迟到责任", 1, 0,
                                        List.of(new com.yumi.production.api.DefectGroupRequest
                                                .ScrapPartRequest(
                                                1, com.yumi.production.api.PhysicalDestination.DESTROYED,
                                                com.yumi.production.api.ResponsibilityType.EMPLOYEE,
                                                employeeId, null)))),
                                null)))));
        asAdmin(() -> payrollIngestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAY-INGEST"))));

        PayrollReviewRef review = asAdmin(() -> reviews.review(
                new ReviewPayrollCommand(uniqueKey("PAY-REVIEW"), view.resultBusinessId())));
        assertThat(review.lineCount()).isPositive();
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_payroll_reference_lines
                        WHERE run_business_id = :runId AND line_type = 'RESPONSIBILITY'
                        """).param("runId", review.reviewRunBusinessId()).query(Long.class).single())
                .isPositive();
        assertThat(payableOf(view.resultBusinessId())).isEqualByComparingTo("1000.00");
        assertThat(confirmedOf(view.resultBusinessId())).isEqualByComparingTo("1000.00");

        var payment = asAdmin(() -> cash.payPayroll(new PayPayrollCommand(
                uniqueKey("PAY-CASH"), List.of(new PayPayrollCommand.PayrollAllocation(
                        view.resultBusinessId(),
                        SettlementMoney.of(new BigDecimal("400.00"), CurrencyCode.CNY))),
                LocalDate.parse("2026-09-30"))));
        assertThat(netPaidOf(view.resultBusinessId())).isEqualByComparingTo("400.00");

        asAdmin(() -> payroll.createPeriod(new CreatePayrollPeriodCommand(
                uniqueKey("PAY-PERIOD-NOV"), "2026-11",
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"))));
        asAdmin(() -> cash.returnPayroll(new ReturnPayrollPaymentCommand(
                uniqueKey("PAY-RETURN"), payment.cashFactBusinessId(), view.resultBusinessId(),
                SettlementMoney.of(new BigDecimal("100.00"), CurrencyCode.CNY),
                LocalDate.parse("2026-10-01"), "员工真实退回", List.of())));
        assertThat(netPaidOf(view.resultBusinessId())).isEqualByComparingTo("300.00");
        assertThat(payableOf(view.resultBusinessId())).isEqualByComparingTo("1000.00");
        assertThat(count("finance_cash_facts")).isEqualTo(2);

        asAdmin(() -> adjustments.adjust(new AdjustPayrollCommand(
                uniqueKey("PAY-ADJUST"), view.resultBusinessId(),
                SignedSettlementAmount.of(new BigDecimal("-100.00")),
                "退回后人工下调应付", review.reviewRunBusinessId())));
        assertThat(payableOf(view.resultBusinessId())).isEqualByComparingTo("900.00");
        assertThat(confirmedOf(view.resultBusinessId())).isEqualByComparingTo("1000.00");
        assertThat(count("finance_direct_labor_cost_adjustments")).isEqualTo(1);
        assertThat(count("finance_cash_facts")).isEqualTo(2);

        asAdmin(() -> gluePolicies.publish(new PublishGlueAllowancePolicyCommand(
                uniqueKey("PAY-GLUE"), LocalDate.parse("2026-10-01"),
                WeightGram.ofGrams("300.0000"), "设置胶水免扣", 0L)));
        GlueMonthReferenceView reference = asAdmin(() -> glueQueries.runMonthReference(
                new RunGlueMonthReferenceCommand(
                        uniqueKey("PAY-GLUE-RUN"), employeeId, LocalDate.parse("2026-10-01"))));
        assertThat(reference.monthStart()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(reference.policyBusinessId()).isNotBlank();
        GlueMonthReferenceView stored = asAdmin(() -> glueQueries.getMonthReference(
                employeeId, LocalDate.parse("2026-10-01")));
        assertThat(stored.referenceBusinessId()).isEqualTo(reference.referenceBusinessId());
    }

    private BigDecimal payableOf(String resultId) {
        return orderJdbc.sql("SELECT payable_amount FROM finance_payroll_results"
                        + " WHERE result_business_id = :id")
                .param("id", resultId).query(BigDecimal.class).single();
    }

    private BigDecimal confirmedOf(String resultId) {
        return orderJdbc.sql("SELECT confirmed_amount FROM finance_payroll_results"
                        + " WHERE result_business_id = :id")
                .param("id", resultId).query(BigDecimal.class).single();
    }

    private BigDecimal netPaidOf(String resultId) {
        return orderJdbc.sql("""
                        SELECT COALESCE(paid_amount, 0) FROM finance_payroll_payment_projections
                        WHERE result_business_id = :id
                        """).param("id", resultId).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    private long processingItemCount(String resultId) {
        return orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_payroll_processing_items
                        WHERE result_business_id = :id
                        """).param("id", resultId).query(Long.class).single();
    }

    private long costEntriesOf(String resultId) {
        return orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_direct_labor_costs
                        WHERE result_business_id = :id
                        """).param("id", resultId).query(Long.class).single();
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
