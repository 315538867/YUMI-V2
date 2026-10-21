package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.CashFactRef;
import com.yumi.finance.cash.api.PayPayrollCommand;
import com.yumi.finance.cash.api.ReturnPayrollPaymentCommand;
import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.ConfirmEmployeePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollConfirmationService;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.orders.api.OrderRef;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.support.AbstractOrderFixtureTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 工资实际部分退回：按原员工支付分配、跨月业务日期、不自动减少工资成本。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §7.1。真实 MySQL 验证：
 * 9 月 2000 元分给甲乙各 1000，10 月甲退 100 → 甲已付 900、乙仍 1000、成本不变。
 */
class PayrollCashReturnIT extends AbstractOrderFixtureTest {

    @Autowired
    private CashApplicationService cash;

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;

    @Autowired
    private PayrollConfirmationService payrollConfirmations;

    @Test
    @DisplayName("部分退回只冲减原员工已付净额，不改变工资成本，不跨员工")
    void partialReturnReducesOnlyOriginalEmployee() {
        List<ConfirmedPayroll> payrolls = confirmedPayrolls(2);
        String firstResult = payrolls.get(0).view().resultBusinessId();
        String secondResult = payrolls.get(1).view().resultBusinessId();

        CashFactRef payment = asAdmin(() -> cash.payPayroll(new PayPayrollCommand(
                uniqueKey("CASH-PAYROLL"),
                List.of(
                        new PayPayrollCommand.PayrollAllocation(firstResult, money("1000.00")),
                        new PayPayrollCommand.PayrollAllocation(secondResult, money("1000.00"))),
                LocalDate.parse("2026-09-30"))));
        assertThat(payment.direction()).isEqualTo("OUT");

        CashFactRef returned = asAdmin(() -> cash.returnPayroll(new ReturnPayrollPaymentCommand(
                uniqueKey("CASH-PAYROLL-RETURN"), payment.cashFactBusinessId(), firstResult,
                money("100.00"), LocalDate.parse("2026-10-10"), "员工退回部分工资", List.of())));
        assertThat(returned.direction()).isEqualTo("IN");
        assertThat(returned.cashType()).isEqualTo("PAYROLL_RETURN");
        assertThat(returned.businessDate()).isEqualTo(LocalDate.parse("2026-10-10"));
        assertThat(paidOf(firstResult)).isEqualByComparingTo("900.00");
        assertThat(paidOf(secondResult)).isEqualByComparingTo("1000.00");
        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_direct_labor_costs
                        WHERE result_business_id = :id
                        """).param("id", firstResult).query(BigDecimal.class).single())
                .as("退回不自动减少工资成本")
                .isEqualByComparingTo("1000.0000");
        assertThat(count("finance_payroll_return_details")).isEqualTo(1);
        assertThat(count("finance_payroll_payment_returns")).isEqualTo(1);
        assertThat(orderJdbc.sql("""
                        SELECT received_amount FROM finance_source_projections
                        WHERE source_type = 'PAYROLL_RESULT' AND source_business_id = :id
                        """).param("id", firstResult).query(BigDecimal.class).single())
                .isEqualByComparingTo("100.00");

        assertThatThrownBy(() -> asAdmin(() -> cash.returnPayroll(
                new ReturnPayrollPaymentCommand(
                        uniqueKey("CASH-PAYROLL-RETURN"), payment.cashFactBusinessId(),
                        firstResult, money("901.00"), LocalDate.parse("2026-10-11"),
                        "超过原分配", List.of()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_RETURN_EXCEEDS_ALLOCATION"));

        assertThatThrownBy(() -> asAdmin(() -> cash.returnPayroll(
                new ReturnPayrollPaymentCommand(
                        uniqueKey("CASH-PAYROLL-RETURN"), payment.cashFactBusinessId(),
                        firstResult, money("10.00"), LocalDate.parse("2026-09-01"),
                        "早于原付款", List.of()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_RETURN_DATE_INVALID"));
        assertThat(paidOf(firstResult)).isEqualByComparingTo("900.00");
        assertThat(paidOf(secondResult)).isEqualByComparingTo("1000.00");
    }

    private List<ConfirmedPayroll> confirmedPayrolls(int count) {
        java.util.List<OrderRef> orders = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            orders.add(completedOrder(2));
        }
        asAdmin(() -> payrollIngestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST"))));
        PayrollPeriodRef period = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAYROLL-PERIOD"), "2026-10",
                        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))));
        asAdmin(() -> payroll.calculate(
                new CalculatePayrollCommand(uniqueKey("PAYROLL-CALC"), period.periodBusinessId())));
        java.util.List<ConfirmedPayroll> confirmed = new java.util.ArrayList<>();
        for (OrderRef order : orders) {
            String worker = orderJdbc.sql("""
                            SELECT e.subject_business_id FROM production_execution_facts e
                            JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                            WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                            """).param("id", order.orderBusinessId()).query(String.class).single();
            EmployeePayrollView view = asAdmin(() -> payrollQuery.getEmployeePayroll(
                    period.periodBusinessId(), worker));
            asAdmin(() -> payrollConfirmations.confirmEmployee(new ConfirmEmployeePayrollCommand(
                    uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(), view.rowVersion(),
                    view.referenceFingerprint(),
                    SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY),
                    "核定工资", List.of())));
            confirmed.add(new ConfirmedPayroll(view));
        }
        return confirmed;
    }

    private BigDecimal paidOf(String resultId) {
        return orderJdbc.sql("""
                        SELECT COALESCE(paid_amount, 0) FROM finance_payroll_payment_projections
                        WHERE result_business_id = :id
                        """).param("id", resultId).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    private static SettlementMoney money(String amount) {
        return SettlementMoney.of(new BigDecimal(amount), CurrencyCode.CNY);
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private record ConfirmedPayroll(EmployeePayrollView view) {
    }
}
