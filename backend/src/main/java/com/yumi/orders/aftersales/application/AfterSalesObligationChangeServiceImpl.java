package com.yumi.orders.aftersales.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.orders.aftersales.api.AfterSalesCancellationRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeService;
import com.yumi.orders.aftersales.api.CancelAfterSalesObligationCommand;
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
import com.yumi.production.api.ChangeRepairRouteCommand;
import com.yumi.production.api.PreparedReturnDisposition;
import com.yumi.production.api.ProductionAfterSalesDemandParticipant;
import com.yumi.production.api.ProductionAfterSalesReturnParticipant;
import com.yumi.production.api.RepairDemandState;
import com.yumi.production.api.ReturnDispositionCommand;
import com.yumi.production.api.ReturnDispositionType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 售后义务取消与分支核对（基线 12 §13.2.1）。
 *
 * <p>池内可用数量新增处置；已路由终态只引用原缺口并变更修复需求，不重复退出；
 * 已交付数量阻止取消。服务端保存本次数量依据。
 */
public class AfterSalesObligationChangeServiceImpl
        implements AfterSalesObligationChangeService {

    private static final String SCOPE = "orders.aftersales.cancel_obligation";

    private final AfterSalesRepository cases;
    private final ProductionAfterSalesDemandParticipant productionDemands;
    private final ProductionAfterSalesReturnParticipant productionReturns;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AfterSalesObligationChangeServiceImpl(
            AfterSalesRepository cases,
            ProductionAfterSalesDemandParticipant productionDemands,
            ProductionAfterSalesReturnParticipant productionReturns,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.productionDemands = productionDemands;
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
    public AfterSalesCancellationRef cancel(CancelAfterSalesObligationCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CANCELLATION", "取消义务命令不能为空");
        }
        if (command.poolDispositionType() == ReturnDispositionType.SCRAPPED) {
            authorization.require(
                    PermissionCodes.PRODUCTION_RESPONSIBILITY_MANAGE, ResourceScope.GLOBAL);
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        SCOPE, command.idempotencyKey(),
                        sha256(command.obligationBusinessId() + "|" + command.reasonText())),
                () -> new CommandOutcome(
                        "AFTER_SALES_OBLIGATION", doCancel(command))).outcome());
        return new AfterSalesCancellationRef(outcome.resultBusinessId(), true);
    }

    private String doCancel(CancelAfterSalesObligationCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        var obligation = cases.findObligation(command.obligationBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_OBLIGATION_NOT_FOUND", "售后义务不存在"));
        cases.lockCase(obligation.caseBusinessId());
        obligation = cases.lockObligation(command.obligationBusinessId()).orElseThrow();
        if ("VOIDED".equals(obligation.status())) {
            return obligation.obligationBusinessId();
        }
        if (obligation.fulfilledQuantity() > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_OBLIGATION_NOT_READY",
                    "已交付数量必须先更正交付与消费，不能借取消直接回池");
        }

        var receipts = cases.findReceiptItems(obligation.obligationBusinessId());
        int received = receipts.stream().mapToInt(AfterSalesRepository.ReceiptItemRecord::quantity).sum();
        int disposedBefore = receipts.stream()
                .mapToInt(item -> cases.disposedQuantityOfReceiptItem(item.receiptItemBusinessId()))
                .sum();

        int routed = 0;
        int gap = 0;
        String repairDemandId = null;
        var repairLink = cases.findProductionLink(obligation.obligationBusinessId(), "REPAIR");
        if (repairLink.isPresent()) {
            repairDemandId = repairLink.get().productionDemandBusinessId();
            RepairDemandState state = productionDemands.describeRepairDemand(repairDemandId);
            if (state.pendingQuantity() > 0 || state.inProcessQuantity() > 0
                    || state.fulfilledQuantity() > 0) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_OBLIGATION_NOT_READY",
                        "修复路线仍有待修复/在制/已交付数量，不能取消");
            }
            routed = state.requiredQuantity();
            gap = state.gapQuantity();
        }

        Map<String, Object> resolution = new LinkedHashMap<>();
        int poolDisposed = 0;
        for (var receipt : receipts) {
            int available = productionReturns.availablePoolQuantity(
                    receipt.productionReturnedSourceBusinessId());
            if (available <= 0) {
                continue;
            }
            if (command.poolDispositionType() == null
                    || command.poolDispositionType() == ReturnDispositionType.RETURN_TO_CUSTOMER) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "AFTER_SALES_RETURN_DISPOSITION_REQUIRED",
                        "取消义务必须为池内可用数量指定报废或非报废释放去向");
            }
            ReturnDispositionCommand productionCommand = new ReturnDispositionCommand(
                    receipt.productionReturnedSourceBusinessId(),
                    command.poolDispositionType(), command.poolPhysicalDestination(), available,
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
                    receipt.receiptItemBusinessId(),
                    receipt.productionReturnedSourceBusinessId(),
                    command.poolDispositionType().name(),
                    command.poolPhysicalDestination().name(),
                    prepared.terminalExitBusinessId(), available,
                    prepared.dispositionFactBusinessId(), prepared.consumptionFactBusinessId(),
                    prepared.transferFactBusinessId(), command.reasonText().strip(),
                    command.businessDate(), now, operatorId));
            productionReturns.commitDisposition(productionCommand, prepared);
            poolDisposed += available;
            resolution.put("pool:" + receipt.receiptItemBusinessId(), available);
        }

        if (gap > 0) {
            productionDemands.commitRouteChange(
                    new ChangeRepairRouteCommand(repairDemandId, gap));
            resolution.put("repairGap:" + repairDemandId, gap);
        }
        int accounted = poolDisposed + routed + disposedBefore;
        if (accounted != received) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_OBLIGATION_NOT_READY",
                    "取消数量分支不守恒：接收 " + received + "，池处置 " + poolDisposed
                            + "，修复路线 " + routed + "，历史处置 " + disposedBefore);
        }

        cases.updateObligationTarget(
                obligation.obligationBusinessId(), null, null, "VOIDED", obligation.rowVersion());
        cases.insertObligationFact(new AfterSalesRepository.ObligationFactRecord(
                "AOF-" + UUID.randomUUID(), obligation.obligationBusinessId(), "CANCEL",
                received, BigDecimal.ZERO, writeJson(resolution), command.reasonText().strip(),
                now, operatorId));

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders",
                "CANCEL_AFTER_SALES_OBLIGATION", "AFTER_SALES_OBLIGATION",
                obligation.obligationBusinessId(), "SUCCESS",
                Map.of("poolDisposed", String.valueOf(poolDisposed),
                        "routed", String.valueOf(routed),
                        "gap", String.valueOf(gap)),
                now));
        return obligation.obligationBusinessId();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_PAYLOAD_INVALID", "取消数量依据无法序列化");
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
