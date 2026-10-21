package com.yumi.orders.aftersales.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.orders.aftersales.api.AfterSalesDeliveryRef;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnRef;
import com.yumi.orders.aftersales.api.ReturnToCustomerCommand;
import com.yumi.orders.aftersales.application.port.AfterSalesRepository;
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
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.PreparedReturnDisposition;
import com.yumi.production.api.PreparedReturnReentry;
import com.yumi.production.api.ProductionAfterSalesReturnParticipant;
import com.yumi.production.api.RegisterReturnCommand;
import com.yumi.production.api.ReturnDispositionCommand;
import com.yumi.production.api.ReturnDispositionResult;
import com.yumi.production.api.ReturnDispositionType;
import com.yumi.production.api.ReturnReentryResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 售后退回接收与原样退还实现（基线 12 §13.2、§13.3）。
 *
 * <p>退回按订单项的最近一次交付明细追溯；退回重入、售后交付与池来源消费在同一事务完成。
 */
public class AfterSalesReturnServiceImpl implements AfterSalesReturnService {

    private static final String RECEIVE_SCOPE = "orders.aftersales.receive_return";
    private static final String RETURN_SCOPE = "orders.aftersales.return_to_customer";

    private final AfterSalesRepository cases;
    private final ProductionAfterSalesReturnParticipant productionReturns;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AfterSalesReturnServiceImpl(
            AfterSalesRepository cases,
            ProductionAfterSalesReturnParticipant productionReturns,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.productionReturns = productionReturns;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ReceiveReturnRef receiveReturn(ReceiveReturnCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_RETURN", "退回命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        RECEIVE_SCOPE, command.idempotencyKey(),
                        sha256(command.obligationBusinessId() + "|" + command.quantity())),
                () -> new CommandOutcome(
                        "AFTER_SALES_RETURN_RECEIPT", doReceive(command))).outcome());
        var item = cases.findReceiptItem(outcome.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_RECEIPT_MISSING", "退回明细无法读取"));
        return new ReceiveReturnRef(
                item.receiptFactBusinessId(), item.receiptItemBusinessId(),
                item.productionReturnedSourceBusinessId(), item.quantity(), item.quantity());
    }

    @Override
    public AfterSalesDeliveryRef returnToCustomer(ReturnToCustomerCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_RETURN", "退还命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        RETURN_SCOPE, command.idempotencyKey(),
                        sha256(command.receiptItemBusinessId() + "|" + command.quantity())),
                () -> new CommandOutcome(
                        "AFTER_SALES_DELIVERY", doReturnToCustomer(command))).outcome());
        var item = cases.findDeliveryItem(outcome.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_DELIVERY_MISSING",
                        "售后交付明细无法读取"));
        return new AfterSalesDeliveryRef(
                item.deliveryFactBusinessId(), item.deliveryItemBusinessId(),
                item.productionConsumptionFactBusinessId(), item.quantity());
    }

    private String doReceive(ReceiveReturnCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        var obligation = cases.findObligation(command.obligationBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_OBLIGATION_NOT_FOUND", "售后义务不存在"));
        cases.lockCase(obligation.caseBusinessId());
        obligation = cases.lockObligation(command.obligationBusinessId()).orElseThrow();
        if (!"RETURN_AND_REPAIR".equals(obligation.obligationType())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_OBLIGATION_NOT_READY",
                    "只有返修义务可以接收退回");
        }
        if (obligation.orderItemBusinessId() == null) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_OBLIGATION_NOT_READY",
                    "返修义务缺少订单项引用");
        }
        int received = cases.receivedQuantityOfObligation(obligation.obligationBusinessId());
        int target = obligation.targetQuantity() == null ? 0 : obligation.targetQuantity();
        if (received + command.quantity() > target) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_QUANTITY_EXCEEDED",
                    "退回数量超过有效返修义务：目标 " + target + "，已接收 " + received);
        }

        var delivery = cases.findLatestDelivery(obligation.orderItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_RETURN_TRACE_REQUIRED",
                        "没有可追溯的最近交付明细"));
        int returned = cases.returnedQuantityOfDeliveryItem(delivery.deliveryItemBusinessId());
        int remaining = delivery.quantity() - returned;
        if (command.quantity() > remaining) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_RETURN_QUANTITY_EXCEEDED",
                    "退回数量超过该交付剩余可退数量：可退 " + remaining);
        }

        String receiptFactId = "ARF-" + UUID.randomUUID();
        String receiptItemId = "ARI-" + UUID.randomUUID();
        PreparedReturnReentry prepared = productionReturns.prepareRegisterReturn(
                new RegisterReturnCommand(
                        receiptItemId, delivery.sourceType(), delivery.productionSourceBusinessId(),
                        delivery.returnedSourceBusinessId(), delivery.consumptionFactBusinessId(),
                        command.quantity()));
        cases.insertReceiptFact(new AfterSalesRepository.ReceiptFactRecord(
                receiptFactId, obligation.obligationBusinessId(), command.quantity(),
                command.conditionCode().strip(), now, operatorId));
        cases.insertReceiptItem(new AfterSalesRepository.ReceiptItemRecord(
                receiptItemId, receiptFactId, obligation.obligationBusinessId(),
                delivery.deliveryType(), delivery.deliveryBusinessId(),
                delivery.deliveryItemBusinessId(), delivery.sourceType(),
                delivery.productionSourceBusinessId(), delivery.returnedSourceBusinessId(),
                delivery.physicalRootSourceBusinessId(), delivery.consumptionFactBusinessId(),
                prepared.returnedSourceBusinessId(), command.quantity()));
        ReturnReentryResult reentry = productionReturns.commitRegisterReturn(prepared);
        if (!"IN_PROGRESS".equals(obligation.status())) {
            cases.updateObligationStatus(
                    obligation.obligationBusinessId(), "IN_PROGRESS", obligation.rowVersion());
        }

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders",
                "RECEIVE_AFTER_SALES_RETURN", "AFTER_SALES_OBLIGATION",
                obligation.obligationBusinessId(), "SUCCESS",
                Map.of("receiptItemId", receiptItemId,
                        "returnedSourceId", reentry.returnedSourceBusinessId(),
                        "quantity", String.valueOf(command.quantity())),
                now));
        return receiptItemId;
    }

    private String doReturnToCustomer(ReturnToCustomerCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        var item = cases.findReceiptItem(command.receiptItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_RECEIPT_NOT_FOUND", "退回接收明细不存在"));
        var obligation = cases.findObligation(item.obligationBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_OBLIGATION_NOT_FOUND", "售后义务不存在"));
        cases.lockCase(obligation.caseBusinessId());
        obligation = cases.lockObligation(obligation.obligationBusinessId()).orElseThrow();
        int disposed = cases.disposedQuantityOfReceiptItem(item.receiptItemBusinessId());
        int remaining = item.quantity() - disposed;
        if (command.quantity() > remaining) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_QUANTITY_EXCEEDED",
                    "退还数量超过接收明细剩余可处置数量：可用 " + remaining);
        }

        String deliveryFactId = "ADF-" + UUID.randomUUID();
        String deliveryItemId = "ADI-" + UUID.randomUUID();
        PreparedReturnDisposition prepared = productionReturns.prepareDisposition(
                new ReturnDispositionCommand(
                        item.productionReturnedSourceBusinessId(),
                        ReturnDispositionType.RETURN_TO_CUSTOMER,
                        PhysicalDestination.CONSUMED, command.quantity(),
                        command.deliveredAt().toLocalDate(), "未修复原样退还客户",
                        "orders", "AFTER_SALES_DELIVERY", deliveryFactId,
                        null, null, null, null, null, null, null, null));
        ReturnDispositionResult disposition = productionReturns.commitDisposition(
                new ReturnDispositionCommand(
                        item.productionReturnedSourceBusinessId(),
                        ReturnDispositionType.RETURN_TO_CUSTOMER,
                        PhysicalDestination.CONSUMED, command.quantity(),
                        command.deliveredAt().toLocalDate(), "未修复原样退还客户",
                        "orders", "AFTER_SALES_DELIVERY", deliveryFactId,
                        null, null, null, null, null, null, null, null),
                prepared);

        cases.insertDeliveryFact(new AfterSalesRepository.DeliveryFactRecord(
                deliveryFactId, obligation.obligationBusinessId(),
                obligation.orderItemBusinessId(), "RETURN_TO_CUSTOMER",
                command.deliveredAt().toInstant(ZoneOffset.UTC), writeJson(command.recipient()),
                command.trackingNo(), operatorId));
        cases.insertDeliveryItem(new AfterSalesRepository.DeliveryItemRecord(
                deliveryItemId, deliveryFactId, "RETURNED_PRODUCT_POOL", null,
                item.productionReturnedSourceBusinessId(), item.physicalRootSourceBusinessId(),
                command.quantity(), disposition.consumptionFactBusinessId()));
        cases.insertDisposition(new AfterSalesRepository.DispositionRecord(
                "ASD-" + UUID.randomUUID(), obligation.obligationBusinessId(),
                item.receiptItemBusinessId(), item.productionReturnedSourceBusinessId(),
                "RETURN_TO_CUSTOMER", "CONSUMED", null, command.quantity(),
                disposition.dispositionFactBusinessId(),
                disposition.consumptionFactBusinessId(), null, "未修复原样退还客户",
                command.deliveredAt().toLocalDate(), now, operatorId));

        int fulfilled = obligation.fulfilledQuantity() + command.quantity();
        int target = obligation.targetQuantity() == null ? fulfilled : obligation.targetQuantity();
        cases.updateObligationProgress(
                obligation.obligationBusinessId(), fulfilled, obligation.fulfilledAmount(),
                fulfilled >= target ? "FULFILLED" : "IN_PROGRESS", obligation.rowVersion());

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders",
                "RETURN_AFTER_SALES_PRODUCT", "AFTER_SALES_OBLIGATION",
                obligation.obligationBusinessId(), "SUCCESS",
                Map.of("deliveryItemId", deliveryItemId,
                        "quantity", String.valueOf(command.quantity())),
                now));
        return deliveryItemId;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_PAYLOAD_INVALID", "售后收货信息无法序列化");
        }
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
