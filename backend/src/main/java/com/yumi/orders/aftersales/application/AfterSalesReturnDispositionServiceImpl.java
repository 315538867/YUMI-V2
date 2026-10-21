package com.yumi.orders.aftersales.application;

import com.yumi.orders.aftersales.api.AfterSalesReturnDispositionService;
import com.yumi.orders.aftersales.api.DisposeReturnedProductCommand;
import com.yumi.orders.aftersales.api.ReturnDispositionRef;
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
import com.yumi.production.api.PreparedReturnDisposition;
import com.yumi.production.api.ProductionAfterSalesReturnParticipant;
import com.yumi.production.api.ReturnDispositionCommand;
import com.yumi.production.api.ReturnDispositionResult;
import com.yumi.production.api.ReturnDispositionType;
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
 * 售后池初次处置编排（基线 12 §13.2、13.3）。
 *
 * <p>orders 锁定案例、义务与接收明细并保存处置记录；production 在同一事务写入池处置、
 * 责任/退出及可选移交。任一步失败全部回滚。
 */
public class AfterSalesReturnDispositionServiceImpl
        implements AfterSalesReturnDispositionService {

    private static final String SCOPE = "orders.aftersales.dispose_returned_product";

    private final AfterSalesRepository cases;
    private final ProductionAfterSalesReturnParticipant productionReturns;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AfterSalesReturnDispositionServiceImpl(
            AfterSalesRepository cases,
            ProductionAfterSalesReturnParticipant productionReturns,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.productionReturns = productionReturns;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ReturnDispositionRef disposeReturnedProduct(DisposeReturnedProductCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DISPOSITION", "池处置命令不能为空");
        }
        if (command.dispositionType() == ReturnDispositionType.SCRAPPED) {
            authorization.require(
                    PermissionCodes.PRODUCTION_RESPONSIBILITY_MANAGE, ResourceScope.GLOBAL);
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        SCOPE, command.idempotencyKey(), fingerprint(command)),
                () -> new CommandOutcome(
                        "AFTER_SALES_RETURN_DISPOSITION", doDispose(command))).outcome());
        var fact = cases.findDisposition(outcome.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_DISPOSITION_MISSING",
                        "售后处置事实无法读取"));
        return new ReturnDispositionRef(
                fact.factBusinessId(), fact.receiptItemBusinessId(),
                fact.productionDispositionFactBusinessId(),
                fact.productionTerminalExitBusinessId(),
                fact.productionOfflineTransferFactBusinessId(), fact.quantity());
    }

    private String doDispose(DisposeReturnedProductCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        var receiptItem = cases.findReceiptItem(command.receiptItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_RECEIPT_NOT_FOUND", "退回接收明细不存在"));
        var obligation = cases.findObligation(receiptItem.obligationBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_OBLIGATION_NOT_FOUND", "售后义务不存在"));
        cases.lockCase(obligation.caseBusinessId());
        cases.lockObligation(obligation.obligationBusinessId());
        int disposed = cases.disposedQuantityOfReceiptItem(command.receiptItemBusinessId());
        int remaining = receiptItem.quantity() - disposed;
        if (command.quantity() > remaining) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_QUANTITY_EXCEEDED",
                    "池处置数量超过接收明细剩余可处置数量：可用 " + remaining);
        }

        ReturnDispositionCommand productionCommand = new ReturnDispositionCommand(
                receiptItem.productionReturnedSourceBusinessId(),
                command.dispositionType(), command.physicalDestination(), command.quantity(),
                command.businessDate(), command.reasonText().strip(),
                null, null, null,
                command.responsibilityType(), command.responsibleSubjectBusinessId(),
                command.waivedReason(), command.offlineInventoryName(),
                command.offlineInventoryNote(), command.handedOverBy(), command.receivedBy(),
                command.transferredAt());
        PreparedReturnDisposition prepared =
                productionReturns.prepareDisposition(productionCommand);
        String dispositionId = "ASD-" + UUID.randomUUID();
        cases.insertDisposition(new AfterSalesRepository.DispositionRecord(
                dispositionId, obligation.obligationBusinessId(),
                command.receiptItemBusinessId(), receiptItem.productionReturnedSourceBusinessId(),
                command.dispositionType().name(), command.physicalDestination().name(),
                prepared.terminalExitBusinessId(), command.quantity(),
                prepared.dispositionFactBusinessId(), prepared.consumptionFactBusinessId(),
                prepared.transferFactBusinessId(), command.reasonText().strip(),
                command.businessDate(), now, operatorId));
        ReturnDispositionResult result =
                productionReturns.commitDisposition(productionCommand, prepared);

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders",
                "DISPOSE_RETURNED_PRODUCT", "AFTER_SALES_RETURN_RECEIPT",
                command.receiptItemBusinessId(), "SUCCESS",
                Map.of("dispositionId", dispositionId,
                        "type", command.dispositionType().name(),
                        "quantity", String.valueOf(command.quantity()),
                        "remaining", String.valueOf(result.availableQuantity())),
                now));
        return dispositionId;
    }

    private static String fingerprint(DisposeReturnedProductCommand command) {
        return sha256(command.receiptItemBusinessId() + "|" + command.quantity() + "|"
                + command.dispositionType() + "|" + command.physicalDestination() + "|"
                + command.responsibilityType() + "|" + command.responsibleSubjectBusinessId()
                + "|" + command.businessDate());
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
