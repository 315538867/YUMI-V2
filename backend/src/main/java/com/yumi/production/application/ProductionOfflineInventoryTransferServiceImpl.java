package com.yumi.production.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.ProductionOfflineInventoryTransferParticipant;
import com.yumi.production.api.TransferPreview;
import com.yumi.production.api.TransferProductionToOfflineCommand;
import com.yumi.production.api.TransferResult;
import com.yumi.production.application.port.ConsumptionRepository;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.SourceRecord;
import com.yumi.production.application.port.VerificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 线下库存单向移交实现（基线 11 §5.7）。
 *
 * <p>可移交数量 = 目标工序就绪余额 − 已消费 − 已移交。移交前必须无活动任务占用；移交与唯一终态
 * 退出同事务提交，来源只扣减一次；移交后不可在系统内更正。
 */
public class ProductionOfflineInventoryTransferServiceImpl
        implements ProductionOfflineInventoryTransferParticipant {

    private final ProductionRepository production;
    private final ConsumptionRepository consumptions;
    private final VerificationRepository verifications;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final Clock clock;

    public ProductionOfflineInventoryTransferServiceImpl(
            ProductionRepository production,
            ConsumptionRepository consumptions,
            VerificationRepository verifications,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock) {
        this.production = production;
        this.consumptions = consumptions;
        this.verifications = verifications;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TransferPreview prepareTransfer(TransferProductionToOfflineCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_TRANSFER, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_TRANSFER_COMMAND", "移交命令不能为空");
        }
        SourceRecord source = lockSource(command.productionSourceBusinessId());
        DemandRecord demand = requireDemand(source);
        requireNoActiveReservation(source);
        int available = availableToTransfer(source, demand);
        if (available < command.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TRANSFER_INSUFFICIENT",
                    "来源可移交数量不足：需要 " + command.quantity() + "，可用 " + available);
        }
        return new TransferPreview(
                "TRF-" + UUID.randomUUID(), "TEX-" + UUID.randomUUID(),
                source.productionSourceBusinessId(), source.physicalRootSourceBusinessId(),
                demand.demandBusinessId(), command.quantity(), available);
    }

    /** 提交移交：命令与准备结果必须一起提交，避免字段被篡改。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TransferResult commit(
            TransferProductionToOfflineCommand command, TransferPreview prepared) {
        authorization.require(PermissionCodes.PRODUCTION_TRANSFER, ResourceScope.GLOBAL);
        if (command == null || prepared == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PREPARED", "移交命令与准备结果不能为空");
        }
        SourceRecord source = lockSource(command.productionSourceBusinessId());
        DemandRecord demand = requireDemand(source);
        requireNoActiveReservation(source);
        requireNotConsumed(source);
        int available = availableToTransfer(source, demand);
        if (available < command.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TRANSFER_INSUFFICIENT",
                    "来源可移交数量不足：需要 " + command.quantity() + "，可用 " + available);
        }

        Instant now = clock.instant();
        consumptions.insertTransfer(new ConsumptionRepository.TransferRecord(
                prepared.transferFactBusinessId(), prepared.terminalExitBusinessId(),
                source.productionSourceBusinessId(), source.physicalRootSourceBusinessId(),
                demand.demandBusinessId(), demand.productBusinessId(), command.reasonCode().name(),
                command.upstreamChangeBusinessId(), command.offlineInventoryName().strip(),
                command.offlineInventoryNote(), command.handedOverBy().strip(),
                command.receivedBy().strip(),
                command.transferredAt().toInstant(ZoneOffset.UTC), command.quantity(), now,
                operatorId()));
        verifications.insertTerminalExit(new VerificationRepository.TerminalExit(
                prepared.terminalExitBusinessId(), source.productionSourceBusinessId(),
                source.physicalRootSourceBusinessId(), demand.demandBusinessId(), null,
                "UPSTREAM_RELEASE",
                com.yumi.production.api.PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY,
                command.quantity(), now));

        // 移交把数量从“目标就绪”移出并记入线下，同时等额减少需求义务：
        // 该数量已随订单减量退出履约（基线 11 §5.6、§5.7）。
        var demandProjection = production.findDemandProjection(demand.demandBusinessId()).orElseThrow();
        int targetReady = demandProjection.targetReadyQuantity() - command.quantity();
        int required = demandProjection.requiredQuantity() - command.quantity();
        if (targetReady < 0 || required < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                    "移交超过目标就绪数量，拒绝提交");
        }
        production.insertDemandProjection(new com.yumi.production.application.port.QuantityProjections
                .DemandProjection(
                        demand.demandBusinessId(), required,
                        demandProjection.pendingWorkQuantity(),
                        demandProjection.inProcessQuantity(), targetReady,
                        demandProjection.fulfilledQuantity(),
                        demandProjection.fulfillmentGapQuantity(), demandProjection.rowVersion() + 1));

        var physical = production.findPhysicalProjection(source.physicalRootSourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "物理投影缺失"));
        int inSystem = physical.inSystemQuantity() - command.quantity();
        int offline = physical.offlineInventoryQuantity() + command.quantity();
        if (inSystem < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                    "移交超过系统内余额，拒绝提交");
        }
        production.insertPhysicalProjection(new com.yumi.production.application.port.QuantityProjections
                .PhysicalProjection(
                        physical.physicalRootSourceBusinessId(),
                        physical.cumulativeMakingCreatedQuantity(), inSystem,
                        physical.consumedQuantity(), physical.destroyedQuantity(), offline,
                        physical.rowVersion() + 1));

        return new TransferResult(
                prepared.transferFactBusinessId(), prepared.terminalExitBusinessId(),
                source.productionSourceBusinessId(), command.quantity(),
                availableToTransfer(source, demand));
    }

    /** 移交后不可在系统内更正：明确拒绝，避免把瑕疵品重新计入可用数量。 */
    public void rejectCorrection(String transferFactBusinessId) {
        authorization.require(PermissionCodes.PRODUCTION_TRANSFER, ResourceScope.GLOBAL);
        if (consumptions.findTransfer(transferFactBusinessId).isEmpty()) {
            throw new ApiException(
                    ApiErrorType.NOT_FOUND, "PRODUCTION_TRANSFER_NOT_FOUND", "移交事实不存在");
        }
        throw new ApiException(
                ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TRANSFER_IRREVERSIBLE",
                "线下库存移交提交后不可在系统内更正，录入错误只能线下处理");
    }

    private SourceRecord lockSource(String productionSourceBusinessId) {
        consumptions.lockSource(productionSourceBusinessId);
        return production.findSource(productionSourceBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_SOURCE_NOT_FOUND", "生产来源不存在"));
    }

    private DemandRecord requireDemand(SourceRecord source) {
        return production.findDemand(source.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));
    }

    private void requireNoActiveReservation(SourceRecord source) {
        if (consumptions.activeReservationOf(source.productionSourceBusinessId()) > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TRANSFER_RESERVED",
                    "来源仍有活动任务占用，不能移交线下");
        }
    }

    private void requireNotConsumed(SourceRecord source) {
        if (consumptions.consumedQuantityOf(source.productionSourceBusinessId()) > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TRANSFER_ALREADY_CONSUMED",
                    "来源已有已消费数量，必须先由下游更正并冲销消费");
        }
    }

    private int availableToTransfer(SourceRecord source, DemandRecord demand) {
        var projection = production.findDemandProjection(demand.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "需求数量投影缺失"));
        // 已消费数量已不在目标就绪中，只需再扣已移交部分
        return projection.targetReadyQuantity()
                - consumptions.transferredQuantityOf(source.productionSourceBusinessId());
    }

    private String operatorId() {
        return currentActor.requireCurrentActor().accountBusinessId().value();
    }
}
