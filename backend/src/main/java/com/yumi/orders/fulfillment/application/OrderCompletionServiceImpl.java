package com.yumi.orders.fulfillment.application;

import com.yumi.orders.api.CompleteOrderCommand;
import com.yumi.orders.api.OrderCompletionRef;
import com.yumi.orders.api.OrderCompletionService;
import com.yumi.orders.application.port.OrderRepository;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单完成实现（基线 12 §5.1、§6、§8.3）。
 *
 * <p>只有交付履约与付款履约同时成立、且不存在未结退款义务时才写完成事实；
 * 完成不修改历史发货、收款或生产事实。
 */
public class OrderCompletionServiceImpl implements OrderCompletionService {

    private static final String SCOPE = "orders.fulfillment.complete";
    private static final String RESULT_TYPE = "ORDER_COMPLETION";

    private final OrderRepository orders;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OrderCompletionServiceImpl(
            OrderRepository orders,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public OrderCompletionRef complete(CompleteOrderCommand command) {
        authorization.require(PermissionCodes.ORDER_COMPLETE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_COMPLETION", "完成命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(SCOPE, command.idempotencyKey(), sha256(command.orderBusinessId())),
                () -> new CommandOutcome(RESULT_TYPE, doComplete(command.orderBusinessId()))).outcome());
        var completion = orders.findCompletion(command.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_COMPLETION_MISSING", "完成事实无法读取"));
        var order = orders.findOrder(command.orderBusinessId()).orElseThrow();
        return new OrderCompletionRef(
                order.orderBusinessId(), completion.occurredAt(), order.contentVersion());
    }

    private String doComplete(String orderBusinessId) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        orders.lockOrder(orderBusinessId);
        var order = orders.findOrder(orderBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"));
        var existing = orders.findCompletion(orderBusinessId);
        if (existing.isPresent()) {
            return existing.get().factBusinessId();
        }
        if ("CANCELLED".equals(order.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_NOT_COMPLETABLE", "已取消订单不能完成");
        }
        var projection = orders.findProjection(orderBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_PROJECTION_MISSING", "订单履约投影缺失"));
        if (!projection.deliveryFulfilled()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_NOT_COMPLETABLE", "交付履约尚未成立");
        }
        if (!projection.paymentFulfilled()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_NOT_COMPLETABLE", "付款履约尚未成立");
        }
        var refundObligation = orders.findRefundObligation(orderBusinessId);
        if (refundObligation.isPresent()
                && refundObligation.get().paidAmount().compareTo(
                        refundObligation.get().decidedAmount()) < 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_NOT_COMPLETABLE",
                    "仍有未结退款义务，不能完成订单");
        }

        String factId = "OCF-" + UUID.randomUUID();
        orders.insertCompletionFact(new OrderRepository.CompletionRecord(
                factId, orderBusinessId, "COMPLETED",
                completionFingerprint(order, projection), now, operatorId));
        orders.markOrderCompleted(orderBusinessId);

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders", "COMPLETE_ORDER",
                "ORDER", orderBusinessId, "SUCCESS",
                Map.of("orderNo", order.orderNo(), "fulfillmentFingerprint",
                        completionFingerprint(order, projection)),
                now));
        return factId;
    }

    private static String completionFingerprint(
            OrderRepository.OrderRecord order, OrderRepository.ProjectionRecord projection) {
        return sha256(order.orderBusinessId() + "|" + projection.requiredQuantity()
                + "|" + projection.shippedQuantity() + "|" + projection.currentReceivable()
                + "|" + projection.receivedAmount() + "|" + projection.refundedAmount());
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
