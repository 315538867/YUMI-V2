package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.CashFactRef;
import com.yumi.finance.cash.api.PayPayrollCommand;
import com.yumi.finance.cash.api.ReturnPayrollPaymentCommand;
import com.yumi.finance.cash.api.ReverseCashFactCommand;
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
 * 并发付款、工资退回与冲销竞争：锁后重算，不允许负数、超付或半笔现金。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §8.3、§10。真实 MySQL
 * READ-COMMITTED 多线程验证，不以 H2 或串行替身替代。
 */
class ConcurrencyAndRollbackIT extends AbstractOrderFixtureTest {

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;

    @Autowired
    private PayrollConfirmationService confirmations;

    @Autowired
    private CashApplicationService cash;

    @Test
    @DisplayName("并发工资付款只有一笔成功，已付不超过应付")
    void concurrentPayrollPaymentCannotOverpay() throws Exception {
        String resultId = confirmedPayroll();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = payAttempt(start, resultId, "600.00");
            Callable<Object> second = payAttempt(start, resultId, "600.00");
            Future<Object> firstResult = executor.submit(first);
            Future<Object> secondResult = executor.submit(second);
            start.countDown();
            List<Object> results = List.of(
                    firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS));

            assertThat(results.stream().filter(CashFactRef.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ApiException.class::isInstance)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(paidOf(resultId)).isEqualByComparingTo("600.00");
        assertThat(count("finance_cash_facts")).isEqualTo(1);
        assertThat(cashNetOfSource(resultId)).isEqualByComparingTo("-600.00");
    }

    @Test
    @DisplayName("工资退回与原付款冲销竞争只有一方成功，不产生负数")
    void payrollReturnAndReversalCompete() throws Exception {
        String resultId = confirmedPayroll();
        CashFactRef payment = asAdmin(() -> cash.payPayroll(new PayPayrollCommand(
                uniqueKey("CONC-PAY"), List.of(new PayPayrollCommand.PayrollAllocation(
                        resultId, money("400.00"))), LocalDate.parse("2026-10-20"))));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> returning = () -> {
                start.await();
                try {
                    return asAdmin(() -> cash.returnPayroll(new ReturnPayrollPaymentCommand(
                            uniqueKey("CONC-RETURN"), payment.cashFactBusinessId(), resultId,
                            money("300.00"), LocalDate.parse("2026-10-20"),
                            "真实退回", List.of())));
                } catch (ApiException exception) {
                    return exception;
                }
            };
            Callable<Object> reversing = () -> {
                start.await();
                try {
                    return asAdmin(() -> cash.reverse(new ReverseCashFactCommand(
                            uniqueKey("CONC-REVERSE"), payment.cashFactBusinessId(),
                            LocalDate.parse("2026-10-20"), "错误付款冲销")));
                } catch (ApiException exception) {
                    return exception;
                }
            };
            Future<Object> returnResult = executor.submit(returning);
            Future<Object> reverseResult = executor.submit(reversing);
            start.countDown();
            List<Object> results = List.of(
                    returnResult.get(20, TimeUnit.SECONDS),
                    reverseResult.get(20, TimeUnit.SECONDS));

            assertThat(results.stream().filter(CashFactRef.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ApiException.class::isInstance)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        BigDecimal netPaid = paidOf(resultId);
        assertThat(netPaid).isBetween(BigDecimal.ZERO, new BigDecimal("400.00"));
        assertThat(netPaid).isEqualByComparingTo(
                count("finance_payroll_payment_returns") == 0 ? "0.00" : "100.00");
        assertThat(count("finance_cash_facts")).isEqualTo(2);
    }

    private Callable<Object> payAttempt(CountDownLatch start, String resultId, String amount) {
        return () -> {
            start.await();
            try {
                return asAdmin(() -> cash.payPayroll(new PayPayrollCommand(
                        uniqueKey("CONC-PAY"), List.of(new PayPayrollCommand.PayrollAllocation(
                                resultId, money(amount))), LocalDate.parse("2026-10-20"))));
            } catch (ApiException exception) {
                return exception;
            }
        };
    }

    private String confirmedPayroll() {
        OrderRef order = completedOrder(2);
        String employeeId = orderJdbc.sql("""
                        SELECT e.subject_business_id FROM production_execution_facts e
                        JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                        WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).param("id", order.orderBusinessId()).query(String.class).single();
        asAdmin(() -> payrollIngestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("CONC-INGEST"))));
        PayrollPeriodRef period = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("CONC-PERIOD"), "2026-10",
                        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))));
        asAdmin(() -> payroll.calculate(
                new CalculatePayrollCommand(uniqueKey("CONC-CALC"), period.periodBusinessId())));
        EmployeePayrollView view = asAdmin(() -> payrollQuery.getEmployeePayroll(
                period.periodBusinessId(), employeeId));
        asAdmin(() -> confirmations.confirmEmployee(new ConfirmEmployeePayrollCommand(
                uniqueKey("CONC-CONFIRM"), view.resultBusinessId(), view.rowVersion(),
                view.referenceFingerprint(),
                SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY),
                "并发测试核定", List.of())));
        return view.resultBusinessId();
    }

    private BigDecimal paidOf(String resultId) {
        return orderJdbc.sql("""
                        SELECT COALESCE(paid_amount, 0) FROM finance_payroll_payment_projections
                        WHERE result_business_id = :id
                        """).param("id", resultId).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal cashNetOfSource(String resultId) {
        return orderJdbc.sql("""
                        SELECT COALESCE(received_amount, 0) - COALESCE(paid_amount, 0)
                        FROM finance_source_projections
                        WHERE source_type = 'PAYROLL_RESULT' AND source_business_id = :id
                        """).param("id", resultId).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private static SettlementMoney money(String amount) {
        return SettlementMoney.of(new BigDecimal(amount), CurrencyCode.CNY);
    }
}
