package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.orders.api.ChangeConfirmedOrderCommand;
import com.yumi.orders.api.CommitOrderPaymentLink;
import com.yumi.orders.api.CompleteOrderCommand;
import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.OrderChangeAction;
import com.yumi.orders.api.OrderChangeKind;
import com.yumi.orders.api.OrderChangeService;
import com.yumi.orders.api.OrderCompletionService;
import com.yumi.orders.api.OrderDeliveryService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderPaymentLinkRef;
import com.yumi.orders.api.OrderPaymentParticipant;
import com.yumi.orders.api.OrderPaymentRequest;
import com.yumi.orders.api.OrderPaymentType;
import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderStatus;
import com.yumi.orders.api.OrderView;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.orders.api.ReverseOrderPaymentLink;
import com.yumi.orders.api.ShipmentItemRequest;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.SchemeView;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskApplicationService;
import com.yumi.production.api.TaskItemRequest;
import com.yumi.production.api.TaskItemResultRequest;
import com.yumi.production.api.TaskRef;
import com.yumi.production.api.TaskType;
import com.yumi.production.api.TaskVerificationService;
import com.yumi.production.api.VerifyTaskCommand;
import com.yumi.support.AbstractOrderFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单应收、退款义务、支付关联与完成。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §5.1、§7.4、§8.2、§10。真实 MySQL 验证：
 * 超义务退款拒绝、付款足但未交付不能完成、收付关联与反向保留原始事实。
 */
class OrderReceivableIT extends AbstractOrderFixtureTest {

    private static final LocalDate WORK_DATE = LocalDate.parse("2026-10-06");
    private static final LocalDateTime SHIPPED_AT = LocalDateTime.parse("2026-10-08T10:30:00");

    @Autowired
    private OrderPaymentParticipant payments;

    @Autowired
    private OrderDeliveryService deliveries;

    @Autowired
    private OrderCompletionService completions;

    @Autowired
    private OrderChangeService orderChanges;

    @Autowired
    private OrderQuery orderQuery;

    @Autowired
    private TaskApplicationService taskService;

    @Autowired
    private TaskVerificationService verificationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("收款不得超过未收余额，只有付款不能完成订单")
    void receiptLimitedByOutstandingAndCannotCompleteAlone() {
        OrderRef order = confirmForMaking(3);

        assertThatThrownBy(() -> pay(order.orderBusinessId(), OrderPaymentType.RECEIPT, "264.01"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ORDER_RECEIPT_EXCEEDS_RECEIVABLE"));
        assertThat(linkCount(order)).isZero();

        pay(order.orderBusinessId(), OrderPaymentType.RECEIPT, "200.00");
        assertThat(projection(order)).containsExactly(
                new BigDecimal("264.00"), new BigDecimal("200.00"), new BigDecimal("0.00"), false, false);
        pay(order.orderBusinessId(), OrderPaymentType.RECEIPT, "64.00");
        assertThat(projection(order)).containsExactly(
                new BigDecimal("264.00"), new BigDecimal("264.00"), new BigDecimal("0.00"), false, true);

        assertThatThrownBy(() -> asAdmin(() -> completions.complete(
                new CompleteOrderCommand(uniqueKey("COMPLETE"), order.orderBusinessId()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ORDER_NOT_COMPLETABLE"));
        assertThat(completionCount(order)).isZero();
    }

    @Test
    @DisplayName("交付与付款都满足后完成订单并写完成事实")
    void completionNeedsBothDeliveryAndPayment() {
        OrderRef order = confirmForMaking(3);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 3);

        assertThatThrownBy(() -> asAdmin(() -> completions.complete(
                new CompleteOrderCommand(uniqueKey("COMPLETE"), order.orderBusinessId()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ORDER_NOT_COMPLETABLE"));

        asAdmin(() -> deliveries.confirmShipment(new com.yumi.orders.api.ConfirmShipmentCommand(
                uniqueKey("SHIP"), order.orderBusinessId(),
                List.of(new ShipmentItemRequest(groupId, sourceId, 3)), "SF9001", SHIPPED_AT)));
        assertThatThrownBy(() -> asAdmin(() -> completions.complete(
                new CompleteOrderCommand(uniqueKey("COMPLETE"), order.orderBusinessId()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).getMessage())
                        .contains("付款履约"));

        pay(order.orderBusinessId(), OrderPaymentType.RECEIPT, "264.00");
        var completion = asAdmin(() -> completions.complete(
                new CompleteOrderCommand(uniqueKey("COMPLETE"), order.orderBusinessId())));

        assertThat(completion.completedAt()).isNotNull();
        assertThat(completionCount(order)).isEqualTo(1);
        OrderView view = asAdmin(() -> orderQuery.get(order.orderBusinessId()));
        assertThat(view.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(view.fulfillment().deliveryFulfilled()).isTrue();
        assertThat(view.fulfillment().paymentFulfilled()).isTrue();
        assertThat(view.fulfillment().blockingReasons()).isEmpty();

        // 重复完成命令幂等复用同一完成事实
        var replay = asAdmin(() -> completions.complete(
                new CompleteOrderCommand(uniqueKey("COMPLETE-2"), order.orderBusinessId())));
        assertThat(replay.completedAt()).isEqualTo(completion.completedAt());
        assertThat(completionCount(order)).isEqualTo(1);
    }

    @Test
    @DisplayName("减价产生的超额收款形成退款义务，超义务退款拒绝且不写关联")
    void refundRequiresAndConsumesObligation() {
        OrderRef order = confirmForMaking(3);
        pay(order.orderBusinessId(), OrderPaymentType.RECEIPT, "264.00");

        asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                uniqueKey("CHANGE"), order.orderBusinessId(), contentVersion(order),
                List.of(new OrderChangeAction(
                        OrderChangeKind.PRICE_OR_RECIPIENT, onlyItemId(order),
                        null,
                        SettlementMoney.of(new BigDecimal("66.00"), CurrencyCode.CNY),
                        null, null, null, null, null)),
                "客户议价")));

        var obligation = obligation(order);
        assertThat(obligation).containsExactly(new BigDecimal("66.00"), new BigDecimal("0.00"), "ACTIVE");
        assertThat(projection(order)).containsExactly(
                new BigDecimal("198.00"), new BigDecimal("264.00"), new BigDecimal("0.00"), false, true);

        assertThatThrownBy(() -> pay(order.orderBusinessId(), OrderPaymentType.REFUND, "66.01"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ORDER_REFUND_EXCEEDS_OBLIGATION"));
        assertThat(linkCount(order)).isEqualTo(1);

        pay(order.orderBusinessId(), OrderPaymentType.REFUND, "66.00");
        assertThat(obligation(order)).containsExactly(
                new BigDecimal("66.00"), new BigDecimal("66.00"), "PAID");
        assertThat(projection(order)).containsExactly(
                new BigDecimal("198.00"), new BigDecimal("264.00"), new BigDecimal("66.00"), false, true);
        assertThat(netReceived(order)).isEqualByComparingTo("198.00");
    }

    @Test
    @DisplayName("没有退款义务时退款拒绝")
    void refundWithoutObligationIsRejected() {
        OrderRef order = confirmForMaking(3);
        pay(order.orderBusinessId(), OrderPaymentType.RECEIPT, "264.00");

        assertThatThrownBy(() -> pay(order.orderBusinessId(), OrderPaymentType.REFUND, "10.00"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ORDER_REFUND_EXCEEDS_OBLIGATION"));
        assertThat(paymentLinkTypes(order)).containsExactly("RECEIPT");
    }

    @Test
    @DisplayName("反向收款保留原事实，净收款回退并阻止完成")
    void reversingReceiptKeepsOriginalFact() {
        OrderRef order = confirmForMaking(3);
        String groupId = onlyGroupId(order);
        String sourceId = onlySourceId(order);
        produce(sourceId, 3);
        asAdmin(() -> deliveries.confirmShipment(new com.yumi.orders.api.ConfirmShipmentCommand(
                uniqueKey("SHIP"), order.orderBusinessId(),
                List.of(new ShipmentItemRequest(groupId, sourceId, 3)), "SF9002", SHIPPED_AT)));
        OrderPaymentLinkRef receipt = pay(order.orderBusinessId(), OrderPaymentType.RECEIPT, "264.00");

        inTransaction(() -> payments.reversePaymentLink(
                new ReverseOrderPaymentLink(receipt.factBusinessId(), "收款录错")));

        assertThat(paymentLinkTypes(order)).containsExactlyInAnyOrder("RECEIPT", "RECEIPT_REVERSAL");
        assertThat(netReceived(order)).isEqualByComparingTo("0.00");
        assertThat(projection(order)).containsExactly(
                new BigDecimal("264.00"), new BigDecimal("0.00"), new BigDecimal("0.00"), true, false);
        assertThatThrownBy(() -> asAdmin(() -> completions.complete(
                new CompleteOrderCommand(uniqueKey("COMPLETE"), order.orderBusinessId()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ORDER_NOT_COMPLETABLE"));
        assertThatThrownBy(() -> inTransaction(() -> payments.reversePaymentLink(
                new ReverseOrderPaymentLink(receipt.factBusinessId(), "重复冲销"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ORDER_PAYMENT_LINK_ALREADY_REVERSED"));
    }

    @Test
    @DisplayName("退款反向恢复未结义务与净收款")
    void reversingRefundRestoresObligation() {
        OrderRef order = confirmForMaking(3);
        pay(order.orderBusinessId(), OrderPaymentType.RECEIPT, "264.00");
        asAdmin(() -> orderChanges.change(new ChangeConfirmedOrderCommand(
                uniqueKey("CHANGE"), order.orderBusinessId(), contentVersion(order),
                List.of(new OrderChangeAction(
                        OrderChangeKind.PRICE_OR_RECIPIENT, onlyItemId(order),
                        null,
                        SettlementMoney.of(new BigDecimal("66.00"), CurrencyCode.CNY),
                        null, null, null, null, null)),
                "客户议价")));
        OrderPaymentLinkRef refund = pay(order.orderBusinessId(), OrderPaymentType.REFUND, "66.00");

        inTransaction(() -> payments.reversePaymentLink(
                new ReverseOrderPaymentLink(refund.factBusinessId(), "退款未实际发生")));

        assertThat(obligation(order)).containsExactly(
                new BigDecimal("66.00"), new BigDecimal("0.00"), "ACTIVE");
        assertThat(netReceived(order)).isEqualByComparingTo("264.00");
        assertThat(projection(order)).containsExactly(
                new BigDecimal("198.00"), new BigDecimal("264.00"), new BigDecimal("0.00"), false, true);
    }

    private OrderRef confirmForMaking(int quantity) {
        CreateConfirmedOrderCommand command = new CreateConfirmedOrderCommand(
                uniqueKey("ORDER"), defaultCustomer.customerBusinessId(), null, ORDER_DATE,
                ORDER_DATE.plusDays(7),
                null,
List.of(new OrderItemRequest(
                        defaultProduct.productBusinessId(), standardSchemeSnapshotRef(),
                        SchemeView.ProcessCode.MAKING, quantity,
                        SettlementMoney.of(new BigDecimal("88.00"), CurrencyCode.CNY),
                        new OrderItemRequest.MaterialSourceAgreement(
                                MaterialSource.STUDIO, MaterialSource.STUDIO,
                                MaterialSource.STUDIO, MaterialSource.STUDIO),
                        new RecipientInfo("张三", "13800000000", "订单收货地址"))));
        return asAdmin(() -> orders.createAndConfirm(command));
    }

    private OrderPaymentLinkRef pay(String orderId, OrderPaymentType type, String amount) {
        return inTransaction(() -> {
            var prepared = payments.preparePayment(new OrderPaymentRequest(
                    orderId, type, SettlementMoney.of(new BigDecimal(amount), CurrencyCode.CNY)));
            return payments.commitPaymentLink(new CommitOrderPaymentLink(
                    prepared, "CASH-" + java.util.UUID.randomUUID()));
        });
    }

    private <T> T inTransaction(java.util.function.Supplier<T> body) {
        return asAdmin(() -> new TransactionTemplate(transactionManager).execute(status -> body.get()));
    }

    private void produce(String sourceId, int quantity) {
        EmployeeRef employee = hireEmployee("收款工人-" + java.util.UUID.randomUUID(), "MAKING");
        TaskRef task = asAdmin(() -> taskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION,
                new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                WORK_DATE, TargetProcess.MAKING,
                List.of(new TaskItemRequest(sourceId, quantity)), null, null, null)));
        String itemId = asAdmin(() -> taskService.getTask(task.taskBusinessId()))
                .items().getFirst().taskItemBusinessId();
        asAdmin(() -> verificationService.verify(new VerifyTaskCommand(
                List.of(new TaskItemResultRequest(itemId, 0, quantity, 0, 0, List.of(), null)))));
    }

    private String onlyGroupId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT g.agreement_group_business_id FROM orders_agreement_groups g
                        JOIN orders_items i ON i.order_item_business_id = g.order_item_business_id
                        WHERE i.order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String onlyItemId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT order_item_business_id FROM orders_items WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private String onlySourceId(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT s.production_source_business_id FROM production_sources s
                        JOIN production_demands d ON d.demand_business_id = s.demand_business_id
                        WHERE d.order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(String.class).single();
    }

    private long contentVersion(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT content_version FROM orders_orders WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Long.class).single();
    }

    private List<Object> projection(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT current_receivable, received_amount, refunded_amount,
                               delivery_fulfilled, payment_fulfilled
                        FROM orders_fulfillment_projections WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId())
                .query((resultSet, rowNumber) -> List.<Object>of(
                        resultSet.getBigDecimal(1), resultSet.getBigDecimal(2), resultSet.getBigDecimal(3),
                        resultSet.getBoolean(4), resultSet.getBoolean(5)))
                .single();
    }

    private List<Object> obligation(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT decided_amount, paid_amount, status FROM orders_refund_obligations
                        WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId())
                .query((resultSet, rowNumber) -> List.<Object>of(
                        resultSet.getBigDecimal(1), resultSet.getBigDecimal(2), resultSet.getString(3)))
                .single();
    }

    private BigDecimal netReceived(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT COALESCE(SUM(
                                   CASE WHEN link_type IN ('RECEIPT', 'REFUND_REVERSAL') THEN linked_amount
                                        WHEN link_type IN ('REFUND', 'RECEIPT_REVERSAL') THEN -linked_amount
                                        ELSE 0 END), 0)
                        FROM orders_payment_link_facts
                        WHERE order_business_id = :id AND is_valid = 1
                        """).param("id", order.orderBusinessId()).query(BigDecimal.class).single();
    }

    private long linkCount(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT COUNT(*) FROM orders_payment_link_facts WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Long.class).single();
    }

    private List<String> paymentLinkTypes(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT link_type FROM orders_payment_link_facts
                        WHERE order_business_id = :id ORDER BY occurred_at, fact_business_id
                        """).param("id", order.orderBusinessId()).query(String.class).list();
    }

    private long completionCount(OrderRef order) {
        return orderJdbc.sql("""
                        SELECT COUNT(*) FROM orders_completion_facts WHERE order_business_id = :id
                        """).param("id", order.orderBusinessId()).query(Long.class).single();
    }
}
