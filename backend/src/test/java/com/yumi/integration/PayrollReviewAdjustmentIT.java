package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.yumi.finance.payroll.api.PayrollReviewService;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.finance.payroll.api.ReviewPayrollCommand;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.http.ApiException;
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
 * 已核定工资只读复核与人工差额调整。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §5.1.4。真实 MySQL 验证：
 * 复核不改原核定；调整追加事实与成本差额；过期引用和负应付拒绝；复核运行不能再次确认。
 */
class PayrollReviewAdjustmentIT extends AbstractOrderFixtureTest {

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

    @Test
    @DisplayName("复核保留原核定，调整追加应付与成本差额，过期引用和负应付拒绝")
    void reviewIsReadOnlyAndAdjustmentAppendsDeltas() {
        Context context = readyPayroll();
        EmployeePayrollView view = context.view();
        String laborLine = view.lines().stream()
                .filter(line -> line.lineType().equals("LABOR"))
                .findFirst().orElseThrow().lineBusinessId();
        var confirmed = asAdmin(() -> confirmations.confirmEmployee(
                new ConfirmEmployeePayrollCommand(
                        uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(),
                        view.rowVersion(), view.referenceFingerprint(),
                        SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY),
                        "十月工资",
                        List.of(new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                laborLine,
                                SettlementMoney.of(new BigDecimal("10.00"), CurrencyCode.CNY),
                                "处理项")))));

        var review = asAdmin(() -> reviews.review(new ReviewPayrollCommand(
                uniqueKey("PAYROLL-REVIEW"), view.resultBusinessId())));
        assertThat(review.reviewRunBusinessId()).isNotBlank();
        assertThat(review.lineCount()).isPositive();
        assertThat(orderJdbc.sql("""
                        SELECT run_type FROM finance_payroll_reference_runs WHERE run_business_id = :id
                        """).param("id", review.reviewRunBusinessId()).query(String.class).single())
                .isEqualTo("REVIEW");
        assertThat(orderJdbc.sql("""
                        SELECT referenced_run_business_id FROM finance_payroll_reference_runs
                        WHERE run_business_id = :id
                        """).param("id", review.reviewRunBusinessId()).query(String.class).single())
                .isEqualTo(view.referenceRunBusinessId());
        assertThat(resultAmount(view.resultBusinessId(), "confirmed_amount"))
                .isEqualByComparingTo("1000.00");
        assertThat(resultAmount(view.resultBusinessId(), "payable_amount"))
                .isEqualByComparingTo("1000.00");
        assertThat(count("finance_direct_labor_cost_adjustments")).isZero();

        // 复核运行不能用于再次确认
        assertThatThrownBy(() -> asAdmin(() -> confirmations.confirmEmployee(
                new ConfirmEmployeePayrollCommand(
                        uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(),
                        view.rowVersion() + 1, review.referenceFingerprint(),
                        SettlementMoney.of(new BigDecimal("900.00"), CurrencyCode.CNY), "再次确认",
                        List.of()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_NOT_READY"));

        var firstAdjustment = asAdmin(() -> adjustments.adjust(new AdjustPayrollCommand(
                uniqueKey("PAYROLL-ADJUST"), view.resultBusinessId(),
                SignedSettlementAmount.of(new BigDecimal("-100.00")),
                "客户投诉补偿", null)));
        assertThat(firstAdjustment.payableAmount().value()).isEqualByComparingTo("900.00");
        assertThat(resultAmount(view.resultBusinessId(), "confirmed_amount"))
                .as("原核定不变").isEqualByComparingTo("1000.00");
        assertThat(orderJdbc.sql("""
                        SELECT amount_delta FROM finance_direct_labor_cost_adjustments
                        """).query(BigDecimal.class).single())
                .isEqualByComparingTo("-100.0000");
        assertThat(count("finance_payroll_processing_items")).isEqualTo(1);

        var second = asAdmin(() -> adjustments.adjust(new AdjustPayrollCommand(
                uniqueKey("PAYROLL-ADJUST"), view.resultBusinessId(),
                SignedSettlementAmount.of(new BigDecimal("-50.00")),
                "引用复核调整", review.reviewRunBusinessId())));
        assertThat(second.payableAmount().value()).isEqualByComparingTo("850.00");
        assertThatThrownBy(() -> asAdmin(() -> adjustments.adjust(new AdjustPayrollCommand(
                uniqueKey("PAYROLL-ADJUST"), view.resultBusinessId(),
                SignedSettlementAmount.of(new BigDecimal("-900.00")),
                "超过应付", null))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_ADJUSTMENT_BELOW_ZERO"));
        assertThatThrownBy(() -> asAdmin(() -> adjustments.adjust(new AdjustPayrollCommand(
                uniqueKey("PAYROLL-ADJUST"), view.resultBusinessId(),
                SignedSettlementAmount.of(new BigDecimal("-10.00")),
                "错误参考", "PYRUN-OTHER"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_REFERENCE_STALE"));

        // 同一调整命令重试不重复追加
        AdjustPayrollCommand retry = new AdjustPayrollCommand(
                uniqueKey("PAYROLL-ADJUST"), view.resultBusinessId(),
                SignedSettlementAmount.of(new BigDecimal("-10.00")), "重试", null);
        asAdmin(() -> adjustments.adjust(retry));
        asAdmin(() -> adjustments.adjust(retry));
        assertThat(count("finance_payroll_adjustments")).isEqualTo(3);
        assertThat(resultAmount(view.resultBusinessId(), "payable_amount"))
                .isEqualByComparingTo("840.00");
    }

    private Context readyPayroll() {
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
        return new Context(view);
    }

    private BigDecimal resultAmount(String resultId, String column) {
        return orderJdbc.sql("SELECT " + column + " FROM finance_payroll_results"
                        + " WHERE result_business_id = :id")
                .param("id", resultId).query(BigDecimal.class).single();
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private record Context(EmployeePayrollView view) {
    }
}
