package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.CommitPayrollPaymentLink;
import com.yumi.finance.payroll.api.ConfirmEmployeePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollConfirmationService;
import com.yumi.finance.payroll.api.PayrollPaymentLinkRef;
import com.yumi.finance.payroll.api.PayrollPaymentParticipant;
import com.yumi.finance.payroll.api.PayrollPaymentRequest;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollReportingFeed;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.finance.payroll.api.ReversePayrollPaymentLink;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工资内部支付参与：仅已核定员工可付，部分付款与多人原子性由调用方事务保证。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §8.2、§10。真实 MySQL 验证：
 * 1000 元应付付 400 后已付 400、待付 600；多人任一失败整体回滚；调用方回滚无残留。
 */
class PayrollPaymentParticipationIT extends AbstractOrderFixtureTest {

    @Autowired
    private PayrollSourceIngestionService payrollIngestion;

    @Autowired
    private PayrollApplicationService payroll;

    @Autowired
    private PayrollQuery payrollQuery;

    @Autowired
    private PayrollConfirmationService confirmations;

    @Autowired
    private PayrollPaymentParticipant payments;

    @Autowired
    private PayrollReportingFeed payrollFeed;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("部分付款校验剩余应付，反向支付恢复已付净额")
    void partialPaymentAndReversal() {
        ConfirmedPayroll confirmed = confirmedPayrolls(1).getFirst();

        PayrollPaymentLinkRef first = pay(confirmed.view().resultBusinessId(), "400.00");
        assertThat(first.paidNetAmount().value()).isEqualByComparingTo("400.00");
        assertThat(first.remainingAmount().value()).isEqualByComparingTo("600.00");

        assertThatThrownBy(() -> pay(confirmed.view().resultBusinessId(), "601.00"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_PAYMENT_EXCEEDS_PAYABLE"));

        var reversal = asAdmin(() -> inTransaction(() -> payments.reversePaymentLink(
                new ReversePayrollPaymentLink(
                        first.linkBusinessId(), "CASH-REVERSAL", "付款录错"))));
        assertThat(reversal.reversal()).isTrue();
        assertThat(reversal.paidNetAmount().value()).isEqualByComparingTo("0.00");
        assertThat(reversal.remainingAmount().value()).isEqualByComparingTo("1000.00");
        assertThat(paidOf(confirmed.view().resultBusinessId()))
                .isEqualByComparingTo("0.00");

        var feed = payrollFeed.changedSince(null, 10);
        assertThat(feed.items()).anySatisfy(item -> {
            assertThat(item.resultBusinessId()).isEqualTo(confirmed.view().resultBusinessId());
            assertThat(item.status()).isEqualTo("CONFIRMED");
            assertThat(item.payableAmount()).isEqualByComparingTo("1000.00");
            assertThat(item.paidAmount()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    @DisplayName("多人付款中任一失败或调用方失败，全部支付关联与已付投影回滚")
    void multiPersonPaymentIsAtomic() {
        List<ConfirmedPayroll> confirmed = confirmedPayrolls(2);
        String firstResult = confirmed.get(0).view().resultBusinessId();
        String secondResult = confirmed.get(1).view().resultBusinessId();

        assertThatThrownBy(() -> asAdmin(() -> inTransaction(() -> {
            var first = payments.preparePayment(new PayrollPaymentRequest(
                    firstResult, money("100.00"), "CASH-A"));
            payments.commitPaymentLink(new CommitPayrollPaymentLink(first, "CASH-A"));
            var second = payments.preparePayment(new PayrollPaymentRequest(
                    secondResult, money("100.00"), "CASH-B"));
            payments.commitPaymentLink(new CommitPayrollPaymentLink(second, "CASH-B"));
            throw new IllegalStateException("调用方现金保存失败");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(count("finance_payroll_payment_links")).isZero();
        assertThat(count("finance_payroll_payment_projections")).isZero();
        assertThat(paidOf(firstResult)).isEqualByComparingTo("0");
        assertThat(paidOf(secondResult)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("未核定员工不能付款")
    void unconfirmedEmployeeCannotBePaid() {
        List<OrderRef> orders = createOrders(1);
        PayrollPeriodRef period = preparePeriodAndCalculate(orders);
        String worker = workerOf(orders.getFirst());
        EmployeePayrollView view = asAdmin(() -> payrollQuery.getEmployeePayroll(
                period.periodBusinessId(), worker));
        assertThat(view.status()).isEqualTo("REFERENCE_READY");

        assertThatThrownBy(() -> asAdmin(() -> inTransaction(() -> payments.preparePayment(
                new PayrollPaymentRequest(
                        view.resultBusinessId(), money("100.00"), "CASH-UNCONFIRMED")))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_NOT_CONFIRMED"));
        assertThat(count("finance_payroll_payment_links")).isZero();
    }

    private List<ConfirmedPayroll> confirmedPayrolls(int count) {
        List<OrderRef> orders = createOrders(count);
        PayrollPeriodRef period = preparePeriodAndCalculate(orders);
        return orders.stream().map(order -> {
            String worker = workerOf(order);
            EmployeePayrollView view = asAdmin(() -> payrollQuery.getEmployeePayroll(
                    period.periodBusinessId(), worker));
            asAdmin(() -> confirmations.confirmEmployee(new ConfirmEmployeePayrollCommand(
                    uniqueKey("PAYROLL-CONFIRM"), view.resultBusinessId(), view.rowVersion(),
                    view.referenceFingerprint(),
                    SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY),
                    "核定工资", List.of())));
            return new ConfirmedPayroll(order, period, view);
        }).toList();
    }

    private List<OrderRef> createOrders(int count) {
        java.util.List<OrderRef> orders = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            orders.add(completedOrder(2));
        }
        return orders;
    }

    private PayrollPeriodRef preparePeriodAndCalculate(List<OrderRef> orders) {
        asAdmin(() -> payrollIngestion.ingest(
                new IngestPayrollSourcesCommand(uniqueKey("PAYROLL-INGEST"))));
        PayrollPeriodRef period = asAdmin(() -> payroll.createPeriod(
                new CreatePayrollPeriodCommand(
                        uniqueKey("PAYROLL-PERIOD"), "2026-10",
                        LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))));
        asAdmin(() -> payroll.calculate(
                new CalculatePayrollCommand(uniqueKey("PAYROLL-CALC"), period.periodBusinessId())));
        return period;
    }

    private PayrollPaymentLinkRef pay(String resultId, String amount) {
        return asAdmin(() -> inTransaction(() -> {
            var prepared = payments.preparePayment(new PayrollPaymentRequest(
                    resultId, money(amount), "CASH-PREP"));
            return payments.commitPaymentLink(new CommitPayrollPaymentLink(
                    prepared, "CASH-" + java.util.UUID.randomUUID()));
        }));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> body.get());
    }

    private static SettlementMoney money(String amount) {
        return SettlementMoney.of(new BigDecimal(amount), CurrencyCode.CNY);
    }

    private String workerOf(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT e.subject_business_id FROM production_execution_facts e
                        JOIN production_demands d ON d.demand_business_id = e.demand_business_id
                        WHERE d.order_business_id = :id AND e.subject_type = 'EMPLOYEE' LIMIT 1
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private BigDecimal paidOf(String resultId) {
        return orderJdbc.sql("""
                        SELECT COALESCE(paid_amount, 0) FROM finance_payroll_payment_projections
                        WHERE result_business_id = :id
                        """).param("id", resultId).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    private long count(String table) {
        return orderJdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private record ConfirmedPayroll(OrderRef order, PayrollPeriodRef period, EmployeePayrollView view) {
    }
}
