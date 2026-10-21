package com.yumi.orders.aftersales.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeRoute;
import com.yumi.orders.aftersales.api.AfterSalesRepairRef;
import com.yumi.orders.aftersales.api.AfterSalesRouteService;
import com.yumi.orders.aftersales.api.ChangeAfterSalesObligationCommand;
import com.yumi.orders.aftersales.api.RequestRepairCommand;
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
import com.yumi.production.api.AfterSalesReplacementDemandRef;
import com.yumi.production.api.ChangeRepairRouteCommand;
import com.yumi.production.api.CreateAfterSalesRepairDemandCommand;
import com.yumi.production.api.CreateAfterSalesReplacementDemandCommand;
import com.yumi.production.api.ProductionAfterSalesDemandParticipant;
import com.yumi.production.api.RepairRoutePreview;
import com.yumi.production.api.RepairRouteResult;
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
 * 售后修复与补发路线实现（基线 12 §13.2 规则 17—18）。
 *
 * <p>返修需求只从退回接收数量建立；修复失败转路线只转换生产当前缺口，
 * 补发同事务建立独立 NEW_PRODUCT 需求，不自动排班。
 */
public class AfterSalesRouteServiceImpl implements AfterSalesRouteService {

    private static final String REPAIR_SCOPE = "orders.aftersales.request_repair";
    private static final String CHANGE_SCOPE = "orders.aftersales.change_obligation";

    private final AfterSalesRepository cases;
    private final ProductionAfterSalesDemandParticipant productionDemands;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AfterSalesRouteServiceImpl(
            AfterSalesRepository cases,
            ProductionAfterSalesDemandParticipant productionDemands,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.productionDemands = productionDemands;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AfterSalesRepairRef requestRepair(RequestRepairCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REPAIR", "返修命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        REPAIR_SCOPE, command.idempotencyKey(),
                        sha256(command.obligationBusinessId() + "|"
                                + command.receiptItemBusinessId() + "|" + command.quantity())),
                () -> new CommandOutcome(
                        "AFTER_SALES_REPAIR_DEMAND", doRequestRepair(command))).outcome());
        var link = cases.findProductionLink(command.obligationBusinessId(), "REPAIR")
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_LINK_MISSING", "返修需求关联缺失"));
        var receiptItem = cases.findReceiptItem(command.receiptItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_RECEIPT_NOT_FOUND", "退回接收明细不存在"));
        return new AfterSalesRepairRef(
                link.productionDemandBusinessId(), link.productionRepairSourceBusinessId(),
                receiptItem.productionReturnedSourceBusinessId(), command.quantity());
    }

    @Override
    public AfterSalesObligationChangeRef changeObligation(
            ChangeAfterSalesObligationCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CHANGE", "义务变更命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        CHANGE_SCOPE, command.idempotencyKey(),
                        sha256(command.sourceObligationBusinessId() + "|" + command.quantity()
                                + "|" + command.route())),
                () -> new CommandOutcome(
                        "AFTER_SALES_OBLIGATION",
                        doChange(command))).outcome());
        String successorId = outcome.resultBusinessId()
                .equals(command.sourceObligationBusinessId())
                ? null : outcome.resultBusinessId();
        return new AfterSalesObligationChangeRef(
                command.sourceObligationBusinessId(), successorId, command.route(),
                command.quantity());
    }

    private String doRequestRepair(RequestRepairCommand command) {
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
                    "只有返修义务可以申请修复");
        }
        var receiptItem = cases.findReceiptItem(command.receiptItemBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_RECEIPT_NOT_FOUND", "退回接收明细不存在"));
        if (!receiptItem.obligationBusinessId().equals(obligation.obligationBusinessId())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_OBLIGATION_NOT_READY",
                    "退回接收明细不属于该售后义务");
        }
        var repair = productionDemands.createRepairDemand(
                new CreateAfterSalesRepairDemandCommand(
                        receiptItem.productionReturnedSourceBusinessId(),
                        obligation.obligationBusinessId(), command.quantity()));
        cases.insertProductionLink(new AfterSalesRepository.ProductionLinkRecord(
                "APL-" + UUID.randomUUID(), obligation.obligationBusinessId(),
                receiptItem.receiptItemBusinessId(), repair.demandBusinessId(),
                repair.productionSourceBusinessId(), "RETURNED_PRODUCT_REPAIR", "REPAIR"));
        if (!"IN_PROGRESS".equals(obligation.status())) {
            cases.updateObligationStatus(
                    obligation.obligationBusinessId(), "IN_PROGRESS", obligation.rowVersion());
        }
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders",
                "REQUEST_AFTER_SALES_REPAIR", "AFTER_SALES_OBLIGATION",
                obligation.obligationBusinessId(), "SUCCESS",
                Map.of("demandBusinessId", repair.demandBusinessId(),
                        "quantity", String.valueOf(command.quantity())),
                now));
        return repair.demandBusinessId();
    }

    private String doChange(ChangeAfterSalesObligationCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        var obligation = cases.findObligation(command.sourceObligationBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_OBLIGATION_NOT_FOUND", "售后义务不存在"));
        cases.lockCase(obligation.caseBusinessId());
        obligation = cases.lockObligation(command.sourceObligationBusinessId()).orElseThrow();
        if (!"RETURN_AND_REPAIR".equals(obligation.obligationType())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_OBLIGATION_NOT_READY",
                    "只有返修义务可以转路线");
        }
        var link = cases.findProductionLink(obligation.obligationBusinessId(), "REPAIR")
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_REPAIR_ROUTE_CHANGE_BLOCKED",
                        "返修义务尚未建立修复需求"));
        RepairRoutePreview preview = productionDemands.previewRouteChange(
                new ChangeRepairRouteCommand(link.productionDemandBusinessId(), command.quantity()));
        if (!preview.allowed()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_REPAIR_ROUTE_CHANGE_BLOCKED",
                    "只能转换当前修复缺口：" + preview.blockingReason());
        }
        RepairRouteResult routeResult = productionDemands.commitRouteChange(
                new ChangeRepairRouteCommand(link.productionDemandBusinessId(), command.quantity()));

        int newTarget = (obligation.targetQuantity() == null ? 0 : obligation.targetQuantity())
                - command.quantity();
        String sourceStatus = newTarget == 0 ? "VOIDED" : obligation.status();
        cases.updateObligationTarget(
                obligation.obligationBusinessId(), newTarget == 0 ? null : newTarget,
                obligation.targetAmount(), sourceStatus, obligation.rowVersion());
        cases.insertObligationFact(new AfterSalesRepository.ObligationFactRecord(
                "AOF-" + UUID.randomUUID(), obligation.obligationBusinessId(), "ROUTE_CHANGE",
                command.quantity(), command.amount() == null ? null : command.amount().value(),
                writeJson(Map.of(
                        "repairDemandBusinessId", link.productionDemandBusinessId(),
                        "route", command.route().name(),
                        "gapBefore", routeResult.gapBefore(),
                        "gapAfter", routeResult.gapAfter())),
                command.note(), now, operatorId));

        String successorId = null;
        if (command.route() == AfterSalesObligationChangeRoute.REPLACEMENT) {
            successorId = insertObligation(
                    obligation, "REPLACEMENT", command.quantity(), null);
            AfterSalesReplacementDemandRef demand = productionDemands.createReplacementDemand(
                    new CreateAfterSalesReplacementDemandCommand(
                            link.productionDemandBusinessId(), successorId, command.quantity()));
            cases.insertProductionLink(new AfterSalesRepository.ProductionLinkRecord(
                    "APL-" + UUID.randomUUID(), successorId, null,
                    demand.demandBusinessId(), demand.productionSourceBusinessId(),
                    "NEW_PRODUCT", "REPLACEMENT"));
        } else if (command.route() == AfterSalesObligationChangeRoute.REFUND
                || command.route() == AfterSalesObligationChangeRoute.COMPENSATION) {
            successorId = insertObligation(
                    obligation, command.route() == AfterSalesObligationChangeRoute.REFUND
                            ? "REFUND" : "COMPENSATION",
                    0, command.amount().value());
        }

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders",
                "CHANGE_AFTER_SALES_OBLIGATION", "AFTER_SALES_OBLIGATION",
                obligation.obligationBusinessId(), "SUCCESS",
                Map.of("route", command.route().name(),
                        "quantity", String.valueOf(command.quantity())),
                now));
        return successorId == null ? obligation.obligationBusinessId() : successorId;
    }

    private String insertObligation(
            AfterSalesRepository.ObligationRecord source, String type, int quantity,
            BigDecimal amount) {
        String id = "ASO-" + UUID.randomUUID();
        cases.insertObligation(new AfterSalesRepository.ObligationRecord(
                id, source.caseBusinessId(), source.orderItemBusinessId(), type,
                "WORKSHOP", source.obligationBusinessId(), null,
                quantity == 0 ? null : quantity, amount, 0, BigDecimal.ZERO,
                null, null, "PENDING", 1L));
        return id;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_PAYLOAD_INVALID", "售后变更依据无法序列化");
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
