package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.CashQuery;
import com.yumi.finance.cash.api.CashReportingFeed;
import com.yumi.finance.cash.api.ReceiveOrderPaymentCommand;
import com.yumi.finance.cash.api.RecordDailyCashCommand;
import com.yumi.finance.cash.api.ReturnPayrollPaymentCommand;
import com.yumi.finance.cash.api.ReverseCashFactCommand;
import com.yumi.finance.cash.api.PayPayrollCommand;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 现金冲销、退回反向与经营费用成对反向。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §7.1、§8.4。真实 MySQL 验证：
 * 原付款存在未冲销退回时不得整笔冲销；并发不能双重反向；费用冲销成对。
 */
class CashReversalIT extends AbstractOrderFixtureTest {

    @Autowired
    private CashApplicationService cash;

    @Autowired
    private CashReportingFeed cashFeed;

    @Autowired
    private CashQuery cashQuery;

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;

    @Autowired
    private PayrollConfirmationService payrollConfirmations;

    @Test
    @DisplayName("订单收款冲销恢复订单与现金投影，并发只反向一次")
    void orderReceiptReversalIsAtomicAndSingle() throws Exception {
        OrderRef order = confirmOrderForMaking(2);
        var receipt = asAdmin(() -> cash.receiveOrder(new ReceiveOrderPaymentCommand(
                uniqueKey("CASH-RECEIPT"), order.orderBusinessId(), money("100.00"),
                LocalDate.parse("2026-10-20"))));

        var reversal = asAdmin(() -> cash.reverse(new ReverseCashFactCommand(
                uniqueKey("CASH-REVERSE"), receipt.cashFactBusinessId(),
                LocalDate.parse("2026-10-20"), "收款录错")));
        assertThat(reversal.cashType()).isEqualTo("REVERSAL");
        assertThat(reversal.direction()).isEqualTo("OUT");
        assertThat(orderJdbc.sql("""
                        SELECT received_amount FROM orders_fulfillment_projections
                        WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId())
                .query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(orderJdbc.sql("""
                        SELECT is_valid FROM finance_cash_facts WHERE cash_fact_business_id = :id
                        """).param("id", receipt.cashFactBusinessId())
                .query(Boolean.class).single()).isFalse();

        // 新收款并发冲销只成功一次
        OrderRef another = confirmOrderForMaking(2);
        var anotherReceipt = asAdmin(() -> cash.receiveOrder(new ReceiveOrderPaymentCommand(
                uniqueKey("CASH-RECEIPT"), another.orderBusinessId(), money("100.00"),
                LocalDate.parse("2026-10-20"))));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = attempt(start, anotherReceipt.cashFactBusinessId());
            Callable<Object> second = attempt(start, anotherReceipt.cashFactBusinessId());
            Future<Object> firstResult = executor.submit(first);
            Future<Object> secondResult = executor.submit(second);
            start.countDown();
            List<Object> results = List.of(
                    firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(ApiException.class::isInstance)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_cash_facts
                        WHERE reverses_fact_business_id = :id
                        """).param("id", anotherReceipt.cashFactBusinessId())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("存在未冲销退回时不能整笔冲销工资付款；先反向退回再冲销付款")
    void payrollPaymentReversalRespectsReturns() {
        ConfirmedPayroll payrollResult = confirmedPayroll();
        var payment = asAdmin(() -> cash.payPayroll(new PayPayrollCommand(
                uniqueKey("CASH-PAYROLL"),
                List.of(new PayPayrollCommand.PayrollAllocation(
                        payrollResult.view().resultBusinessId(), money("1000.00"))),
                LocalDate.parse("2026-09-30"))));
        var returned = asAdmin(() -> cash.returnPayroll(new ReturnPayrollPaymentCommand(
                uniqueKey("CASH-PAYROLL-RETURN"), payment.cashFactBusinessId(),
                payrollResult.view().resultBusinessId(), money("100.00"),
                LocalDate.parse("2026-10-10"), "员工退回", List.of())));

        assertThatThrownBy(() -> asAdmin(() -> cash.reverse(new ReverseCashFactCommand(
                uniqueKey("CASH-REVERSE"), payment.cashFactBusinessId(),
                LocalDate.parse("2026-10-12"), "整笔冲销"))))
                .isInstanceOf(ApiException.class);

        asAdmin(() -> cash.reverse(new ReverseCashFactCommand(
                uniqueKey("CASH-REVERSE"), returned.cashFactBusinessId(),
                LocalDate.parse("2026-10-12"), "退回录错")));
        assertThat(paidOf(payrollResult.view().resultBusinessId()))
                .isEqualByComparingTo("1000.00");

        asAdmin(() -> cash.reverse(new ReverseCashFactCommand(
                uniqueKey("CASH-REVERSE"), payment.cashFactBusinessId(),
                LocalDate.parse("2026-10-12"), "付款录错")));
        assertThat(paidOf(payrollResult.view().resultBusinessId())).isEqualByComparingTo("0");
        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_direct_labor_costs
                        WHERE result_business_id = :id
                        """).param("id", payrollResult.view().resultBusinessId())
                .query(BigDecimal.class).single())
                .as("现金冲销不改变工资成本")
                .isEqualByComparingTo("1000.0000");
    }

    @Test
    @DisplayName("日常费用冲销成对反向，现金与成本净额归零")
    void expenseReversalReversesCost() {
        var expense = asAdmin(() -> cash.recordDailyCash(new RecordDailyCashCommand(
                uniqueKey("CASH-DAILY"), "OUT", "RENT", LocalDate.parse("2026-10-05"),
                money("50.00"), "房租", true)));
        asAdmin(() -> cash.reverse(new ReverseCashFactCommand(
                uniqueKey("CASH-REVERSE"), expense.cashFact().cashFactBusinessId(),
                LocalDate.parse("2026-10-06"), "录错冲销")));

        assertThat(orderJdbc.sql("""
                        SELECT SUM(amount) FROM finance_cost_entries
                        WHERE cost_category = 'OPERATING_EXPENSE'
                        """).query(BigDecimal.class).single()).isEqualByComparingTo("0");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM finance_operating_expenses
                        WHERE operation_type = 'REVERSE'
                        """).query(Long.class).single()).isEqualTo(1);

        var summary = cashQuery.getPeriodSummary(
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));
        assertThat(summary.netAmount()).isEqualByComparingTo(
                summary.income().subtract(summary.outflow()));
        assertThat(cashQuery.search(
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), "REVERSAL"))
                .isNotEmpty();
        assertThat(cashFeed.changedSince(null, 500).items())
                .anySatisfy(item -> assertThat(item.cashType()).isEqualTo("REVERSAL"));
    }

    private Callable<Object> attempt(CountDownLatch start, String cashFactId) {
        return () -> {
            start.await();
            try {
                return asAdmin(() -> cash.reverse(new ReverseCashFactCommand(
                        uniqueKey("CASH-REVERSE"), cashFactId,
                        LocalDate.parse("2026-10-20"), "并发冲销")));
            } catch (ApiException exception) {
                return exception;
            }
        };
    }

    private ConfirmedPayroll confirmedPayroll() {
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
        asAdmin(() -> payrollConfirmations.confirmEmployee(new ConfirmEmployeePayrollCommand(
                uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(), view.rowVersion(),
                view.referenceFingerprint(),
                SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY),
                "核定工资", List.of())));
        return new ConfirmedPayroll(view);
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

    private record ConfirmedPayroll(EmployeePayrollView view) {
    }
}
