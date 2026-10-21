package com.yumi.orders.receivable.application;

import com.yumi.orders.api.CommitOrderPaymentLink;
import com.yumi.orders.api.OrderPaymentLinkRef;
import com.yumi.orders.api.OrderPaymentParticipant;
import com.yumi.orders.api.OrderPaymentRequest;
import com.yumi.orders.api.OrderPaymentType;
import com.yumi.orders.api.PreparedOrderPayment;
import com.yumi.orders.api.ReverseOrderPaymentLink;
import com.yumi.orders.application.port.OrderRepository;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单收付参与实现（基线 12 §7.4、§8.2）。
 *
 * <p>资格在调用方本地事务内校验并写关联事实；真实现金、凭证和冲销由 finance 拥有。
 * 退款只允许在订单有效退款义务剩余额度内发生；反向关联追加记录，不删除原事实。
 */
public class OrderPaymentParticipantImpl implements OrderPaymentParticipant {

    private final OrderRepository orders;
    private final CurrentActorProvider currentActor;
    private final Clock clock;

    public OrderPaymentParticipantImpl(
            OrderRepository orders, CurrentActorProvider currentActor, Clock clock) {
        this.orders = orders;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedOrderPayment preparePayment(OrderPaymentRequest request) {
        validate(request);
        return new PreparedOrderPayment(
                request.orderBusinessId(), request.paymentType(), request.amount());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderPaymentLinkRef commitPaymentLink(CommitOrderPaymentLink command) {
        PreparedOrderPayment prepared = command.prepared();
        validate(new OrderPaymentRequest(
                prepared.orderBusinessId(), prepared.paymentType(), prepared.amount()));

        Instant now = clock.instant();
        String factId = "OPL-" + UUID.randomUUID();
        try {
            orders.insertPaymentLink(new OrderRepository.PaymentLinkRecord(
                    factId, prepared.orderBusinessId(), command.financeCashFactBusinessId(),
                    prepared.paymentType().name(), prepared.amount().value(), null, null,
                    now, operatorId()));
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(
                    ApiErrorType.IDEMPOTENCY_CONFLICT, "ORDER_PAYMENT_LINK_DUPLICATED",
                    "同一现金事实不能重复关联同一订单");
        }

        if (prepared.paymentType() == OrderPaymentType.REFUND) {
            applyRefundToObligation(prepared.orderBusinessId(), prepared.amount().value());
        }
        refreshPaymentState(prepared.orderBusinessId());
        return new OrderPaymentLinkRef(
                factId, prepared.orderBusinessId(), prepared.paymentType(),
                prepared.amount(), false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderPaymentLinkRef reversePaymentLink(ReverseOrderPaymentLink command) {
        var original = orders.findPaymentLink(command.paymentLinkFactBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_PAYMENT_LINK_NOT_FOUND", "订单支付关联不存在"));
        if (original.reversesFactBusinessId() != null) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_PAYMENT_LINK_REVERSAL_INVALID",
                    "反向关联不能再被反向");
        }
        orders.lockOrder(original.orderBusinessId());
        if (orders.findReversalOf(original.factBusinessId()).isPresent()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_PAYMENT_LINK_ALREADY_REVERSED",
                    "该支付关联已经反向，不能重复冲销");
        }

        OrderPaymentType originalType = OrderPaymentType.valueOf(original.linkType());
        String reversalType = originalType.name() + "_REVERSAL";
        Instant now = clock.instant();
        String factId = "OPL-" + UUID.randomUUID();
        orders.insertPaymentLink(new OrderRepository.PaymentLinkRecord(
                factId, original.orderBusinessId(),
                command.financeCashFactBusinessId() == null
                        ? original.financeCashFactBusinessId()
                        : command.financeCashFactBusinessId(),
                reversalType, original.linkedAmount(), original.factBusinessId(),
                original.factBusinessId(), now, operatorId()));

        if (originalType == OrderPaymentType.REFUND) {
            applyRefundReversalToObligation(original.orderBusinessId(), original.linkedAmount());
        }
        refreshPaymentState(original.orderBusinessId());
        return new OrderPaymentLinkRef(
                factId, original.orderBusinessId(), originalType,
                com.yumi.platform.api.value.SettlementMoney.of(
                        original.linkedAmount(), com.yumi.platform.api.value.CurrencyCode.CNY),
                true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String findPaymentLink(String financeCashFactBusinessId) {
        return orders.findPaymentLinkByCashFact(financeCashFactBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_PAYMENT_LINK_NOT_FOUND", "订单支付关联不存在"))
                .factBusinessId();
    }

    private void validate(OrderPaymentRequest request) {
        orders.lockOrder(request.orderBusinessId());
        var order = orders.findOrder(request.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"));
        var projection = orders.findProjection(request.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_PROJECTION_MISSING", "订单履约投影缺失"));

        if (request.paymentType() == OrderPaymentType.RECEIPT) {
            if ("CANCELLED".equals(order.status())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "ORDER_PAYMENT_NOT_ALLOWED",
                        "已取消订单不能收款");
            }
            BigDecimal outstanding = projection.currentReceivable()
                    .subtract(projection.receivedAmount())
                    .add(projection.refundedAmount());
            if (request.amount().value().compareTo(outstanding) > 0) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "ORDER_RECEIPT_EXCEEDS_RECEIVABLE",
                        "收款超过订单未收余额：可收 " + outstanding.setScale(2).toPlainString());
            }
            return;
        }

        orders.lockRefundObligation(request.orderBusinessId());
        var obligation = orders.findRefundObligation(request.orderBusinessId());
        BigDecimal remaining = obligation
                .map(row -> row.decidedAmount().subtract(row.paidAmount()))
                .orElse(BigDecimal.ZERO);
        if (remaining.signum() <= 0 || request.amount().value().compareTo(remaining) > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_REFUND_EXCEEDS_OBLIGATION",
                    "退款超过订单有效退款义务：可退 " + remaining.setScale(2).toPlainString());
        }
    }

    private void applyRefundToObligation(String orderBusinessId, BigDecimal amount) {
        var obligation = orders.findRefundObligation(orderBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_REFUND_OBLIGATION_MISSING",
                        "退款义务不翼而飞，拒绝提交"));
        BigDecimal paid = obligation.paidAmount().add(amount);
        String status = paid.compareTo(obligation.decidedAmount()) >= 0 ? "PAID" : "ACTIVE";
        orders.updateRefundObligation(
                obligation.refundBusinessId(), obligation.decidedAmount(), paid, status);
    }

    private void applyRefundReversalToObligation(String orderBusinessId, BigDecimal amount) {
        var obligation = orders.findRefundObligation(orderBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_REFUND_OBLIGATION_MISSING",
                        "退款义务不翼而飞，拒绝提交"));
        BigDecimal paid = obligation.paidAmount().subtract(amount).max(BigDecimal.ZERO);
        String status = obligation.decidedAmount().signum() == 0
                ? "CLOSED"
                : (paid.compareTo(obligation.decidedAmount()) >= 0 ? "PAID" : "ACTIVE");
        orders.updateRefundObligation(
                obligation.refundBusinessId(), obligation.decidedAmount(), paid, status);
    }

    private void refreshPaymentState(String orderBusinessId) {
        var order = orders.findOrder(orderBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"));
        var projection = orders.findProjection(orderBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_PROJECTION_MISSING", "订单履约投影缺失"));

        BigDecimal received = orders.receivedAmountOf(orderBusinessId);
        BigDecimal refunded = orders.refundedAmountOf(orderBusinessId);
        boolean paymentFulfilled = received.subtract(refunded)
                .compareTo(order.currentReceivableAmount()) >= 0;
        boolean deliveryFulfilled = projection.shippedQuantity() >= projection.requiredQuantity();
        String blocking = blockingReasons(deliveryFulfilled, paymentFulfilled);
        orders.insertFulfillmentProjection(new OrderRepository.ProjectionRecord(
                orderBusinessId, projection.requiredQuantity(), projection.shippedQuantity(),
                order.currentReceivableAmount(), received, refunded,
                deliveryFulfilled, paymentFulfilled, blocking, 1L));
        orders.updateOrderDeliveryFulfilled(
                orderBusinessId, deliveryFulfilled, paymentFulfilled, order.contentVersion());
    }

    private static String blockingReasons(boolean deliveryFulfilled, boolean paymentFulfilled) {
        String reasons = "";
        if (!deliveryFulfilled) {
            reasons = "DELIVERY_PENDING";
        }
        if (!paymentFulfilled) {
            reasons = reasons.isBlank() ? "PAYMENT_PENDING" : reasons + ",PAYMENT_PENDING";
        }
        return reasons.isBlank() ? null : reasons;
    }

    private String operatorId() {
        return currentActor.requireCurrentActor().accountBusinessId().value();
    }
}
