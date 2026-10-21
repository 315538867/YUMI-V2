package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.PayPayrollCommand;
import com.yumi.finance.cash.api.ReceiveOrderPaymentCommand;
import com.yumi.finance.cash.api.RefundAfterSalesPaymentCommand;
import com.yumi.finance.cash.api.RefundOrderPaymentCommand;
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
import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.api.ChangeConfirmedOrderCommand;
import com.yumi.orders.api.OrderChangeAction;
import com.yumi.orders.api.OrderChangeKind;
import com.yumi.orders.api.OrderChangeService;
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
 * 订单、售后与工资真实现金收付。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §8.2、§8.3。真实 MySQL 验证：
 * 1000 元应付付 400 后现金流出 400、已付 400、待付 600；多人任一失败全回滚；重试不重复付款。
 */
class CashSourcePaymentIT extends AbstractOrderFixtureTest {

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

    @Autowired
    private AfterSalesApplicationService afterSales;

    @Autowired
    private OrderChangeService orderChanges;

    @Test
    @DisplayName("订单收款退款、工资部分付款与售后赔付各自原子入账")
    void cashSourcesAreRecordedAtomically() {
        OrderRef receiptOrder = confirmOrderForMaking(2);
        List<ConfirmedPayroll> payrolls = confirmedPayrolls(2);
        OrderRef afterSalesOrder = completedOrder(1);
        String refundObligation = openRefundCase(afterSalesOrder, "50.00");

        var receipt = asAdmin(() -> cash.receiveOrder(new ReceiveOrderPaymentCommand(
                uniqueKey("CASH-RECEIVE"), receiptOrder.orderBusinessId(),
                money("100.00"), LocalDate.parse("2026-10-20"))));
        assertThat(receipt.direction()).isEqualTo("IN");
        assertThat(receipt.cashType()).isEqualTo("ORDER_RECEIPT");
        assertThat(orderJdbc.sql("""
                        SELECT received_amount FROM orders_fulfillment_projections
                        WHERE order_business_id = :id
                        """).param("id", receiptOrder.orderBusinessId())
                .query(BigDecimal.class).single()).isEqualByComparingTo("100.00");

        // 减价形成超额收款退款义务，再走真实退款
        long contentVersion = orderJdbc.sql("""
                        SELECT content_version FROM orders_orders WHERE order_business_id = :id
                        """).param("id", receiptOrder.orderBusinessId()).query(Long.class).single();
        String orderItemId = orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", receiptOrder.orderBusinessId()).query(String.class).single();
        asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                uniqueKey("ORDER-CHANGE"), receiptOrder.orderBusinessId(), contentVersion,
                List.of(new OrderChangeAction(
                        OrderChangeKind.PRICE_OR_RECIPIENT, orderItemId, null,
                        SettlementMoney.of(new BigDecimal("40.00"), CurrencyCode.CNY),
                        null, null, null, null, null)),
                "客户议价")));
        var refund = asAdmin(() -> cash.refundOrder(new RefundOrderPaymentCommand(
                uniqueKey("CASH-REFUND"), receiptOrder.orderBusinessId(),
                money("20.00"), LocalDate.parse("2026-10-20"))));
        assertThat(refund.direction()).isEqualTo("OUT");
        assertThat(orderJdbc.sql("""
                        SELECT refunded_amount FROM orders_fulfillment_projections
                        WHERE order_business_id = :id
                        """).param("id", receiptOrder.orderBusinessId())
                .query(BigDecimal.class).single()).isEqualByComparingTo("20.00");

        PayPayrollCommand payrollCommand = new PayPayrollCommand(
                uniqueKey("CASH-PAYROLL"),
                List.of(new PayPayrollCommand.PayrollAllocation(
                        payrolls.get(0).view().resultBusinessId(), money("400.00"))),
                LocalDate.parse("2026-10-20"));
        var payrollPayment = asAdmin(() -> cash.payPayroll(payrollCommand));
        assertThat(payrollPayment.direction()).isEqualTo("OUT");
        assertThat(paidOf(payrolls.get(0).view().resultBusinessId()))
                .isEqualByComparingTo("400.00");
        assertThat(orderJdbc.sql("""
                        SELECT payable_amount - COALESCE((
                            SELECT paid_amount FROM finance_payroll_payment_projections p
                            WHERE p.result_business_id = r.result_business_id), 0)
                        FROM finance_payroll_results r WHERE result_business_id = :id
                        """).param("id", payrolls.get(0).view().resultBusinessId())
                .query(BigDecimal.class).single())
                .isEqualByComparingTo("600.00");
        // 幂等重试不重复付款
        asAdmin(() -> cash.payPayroll(payrollCommand));
        assertThat(paidOf(payrolls.get(0).view().resultBusinessId()))
                .isEqualByComparingTo("400.00");
        assertThat(count("finance_cash_facts")).isEqualTo(3);

        var afterSalesPayment = asAdmin(() -> cash.refundAfterSales(
                new RefundAfterSalesPaymentCommand(
                        uniqueKey("CASH-AFTER-SALES"), refundObligation,
                        money("50.00"), LocalDate.parse("2026-10-20"))));
        assertThat(afterSalesPayment.cashType()).isEqualTo("AFTER_SALES_REFUND");
        assertThat(orderJdbc.sql("""
                        SELECT fulfilled_amount, status FROM after_sales_obligations
                        WHERE obligation_business_id = :id
                        """).param("id", refundObligation)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getBigDecimal(1).toPlainString(), resultSet.getString(2)))
                .single())
                .containsExactly("50.00", "FULFILLED");

        // 多人付款任一超额时整笔回滚
        assertThatThrownBy(() -> asAdmin(() -> cash.payPayroll(new PayPayrollCommand(
                uniqueKey("CASH-MULTI"),
                List.of(
                        new PayPayrollCommand.PayrollAllocation(
                                payrolls.get(0).view().resultBusinessId(), money("100.00")),
                        new PayPayrollCommand.PayrollAllocation(
                                payrolls.get(1).view().resultBusinessId(), money("1200.00"))),
                LocalDate.parse("2026-10-20")))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PAYROLL_PAYMENT_EXCEEDS_PAYABLE"));
        assertThat(count("finance_cash_facts")).isEqualTo(4);
        assertThat(paidOf(payrolls.get(0).view().resultBusinessId()))
                .isEqualByComparingTo("400.00");
        assertThat(paidOf(payrolls.get(1).view().resultBusinessId())).isEqualByComparingTo("0");

        assertThat(orderJdbc.sql("""
                        SELECT SUM(paid_amount) FROM finance_source_projections
                        WHERE source_type = 'PAYROLL_RESULT'
                        """).query(BigDecimal.class).single())
                .isEqualByComparingTo("400.00");
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
            confirmed.add(new ConfirmedPayroll(order, view));
        }
        return confirmed;
    }

    private String openRefundCase(OrderRef order, String amount) {
        AfterSalesCaseRef caseRef = asAdmin(() -> afterSales.open(new OpenAfterSalesCaseCommand(
                uniqueKey("AS-OPEN"), order.orderBusinessId(), "售后赔付",
                AfterSalesRouteType.REFUND_ONLY,
                List.of(new AfterSalesObligationRequest(
                        AfterSalesObligationType.REFUND, null,
                        money(amount), LocalDate.parse("2026-10-20"), "赔付客户", null)))));
        return orderJdbc.sql("""
                        SELECT obligation_business_id FROM after_sales_obligations
                        WHERE case_business_id = :id AND obligation_type = 'REFUND'
                        """).param("id", caseRef.caseBusinessId()).query(String.class).single();
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

    private record ConfirmedPayroll(OrderRef order, EmployeePayrollView view) {
    }
}
