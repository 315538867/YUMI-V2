package com.yumi.production.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.ConsumeProductionCommand;
import com.yumi.production.api.ConsumptionPreview;
import com.yumi.production.api.ConsumptionResult;
import com.yumi.production.api.ProductionConsumptionParticipant;
import com.yumi.production.api.ReverseConsumptionCommand;
import com.yumi.production.application.port.ConsumptionRepository;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.SourceRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生产来源消费实现（基线 11 §5.2.1、§7.7）。
 *
 * <p>可消费数量 = 目标工序就绪余额 − 已消费 − 已移交线下。已移交数量既不能消费也不能取回；
 * 消费与交付事实同事务提交，交付失败则整体回滚，不留下消费。
 */
public class ProductionConsumptionServiceImpl implements ProductionConsumptionParticipant {

    private final ProductionRepository production;
    private final ConsumptionRepository consumptions;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final Clock clock;

    public ProductionConsumptionServiceImpl(
            ProductionRepository production,
            ConsumptionRepository consumptions,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock) {
        this.production = production;
        this.consumptions = consumptions;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumptionPreview prepareConsume(ConsumeProductionCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_CONSUME, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CONSUME_COMMAND", "消费命令不能为空");
        }
        SourceRecord source = lockSource(command.productionSourceBusinessId());
        DemandRecord demand = requireDemand(source);
        if (activeConsumption(command.delivery()).isPresent()) {
            throw new ApiException(
                    ApiErrorType.IDEMPOTENCY_CONFLICT, "PRODUCTION_CONSUME_DUPLICATED",
                    "同一交付已经消费过该来源，需先反向再重新消费");
        }

        int available = availableToConsume(source, demand);
        if (available < command.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CONSUME_INSUFFICIENT",
                    "来源可消费数量不足：需要 " + command.quantity() + "，可用 " + available);
        }
        return new ConsumptionPreview(
                command.delivery(), "CNF-" + UUID.randomUUID(), source.productionSourceBusinessId(),
                source.physicalRootSourceBusinessId(), demand.demandBusinessId(),
                demand.productBusinessId(), command.quantity(), available, false, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumptionResult commitConsume(ConsumptionPreview prepared) {
        authorization.require(PermissionCodes.PRODUCTION_CONSUME, ResourceScope.GLOBAL);
        if (prepared == null || prepared.reverse()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PREPARED", "消费准备结果不能为空");
        }
        SourceRecord source = lockSource(prepared.productionSourceBusinessId());
        DemandRecord demand = requireDemand(source);
        int available = availableToConsume(source, demand);
        if (available < prepared.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CONSUME_INSUFFICIENT",
                    "来源可消费数量不足：需要 " + prepared.quantity() + "，可用 " + available);
        }

        Instant now = clock.instant();
        consumptions.insertConsumption(new ConsumptionRepository.ConsumptionRecord(
                prepared.consumptionFactBusinessId(), "CONSUME", null,
                prepared.delivery().sourceModule(), prepared.delivery().sourceType(),
                prepared.delivery().sourceBusinessId(), source.productionSourceBusinessId(),
                source.physicalRootSourceBusinessId(), demand.demandBusinessId(),
                demand.productBusinessId(), prepared.quantity(), now, operatorId()));

        applyProjections(demand, source, prepared.quantity(), true);
        return new ConsumptionResult(
                prepared.consumptionFactBusinessId(), source.productionSourceBusinessId(),
                prepared.quantity(), consumptions.consumedQuantityOf(source.productionSourceBusinessId()),
                availableToConsume(source, demand), false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumptionPreview prepareReverse(ReverseConsumptionCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_CONSUME, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CONSUME_COMMAND", "反向命令不能为空");
        }
        SourceRecord source = lockSource(command.productionSourceBusinessId());
        DemandRecord demand = requireDemand(source);
        ConsumptionRepository.ConsumptionRecord consumed = activeConsumption(command.delivery())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CONSUME_NOT_FOUND",
                        "该交付没有可冲销的消费事实"));
        int remaining = remainingOf(consumed);
        if (remaining < command.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CONSUME_INSUFFICIENT",
                    "可冲销数量不足：需要 " + command.quantity() + "，剩余有效消费 " + remaining);
        }
        // 已移交线下的数量不能通过反向消费取回
        int transferred = consumptions.transferredQuantityOf(source.productionSourceBusinessId());
        if (transferred > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_TRANSFER_IRREVERSIBLE",
                    "来源已有数量移交线下，不能反向消费取回");
        }
        return new ConsumptionPreview(
                command.delivery(), "CNF-" + UUID.randomUUID(), source.productionSourceBusinessId(),
                source.physicalRootSourceBusinessId(), demand.demandBusinessId(),
                demand.productBusinessId(), command.quantity(),
                consumptions.consumedQuantityOf(source.productionSourceBusinessId()), true,
                command.reason());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumptionResult commitReverse(ConsumptionPreview prepared) {
        authorization.require(PermissionCodes.PRODUCTION_CONSUME, ResourceScope.GLOBAL);
        if (prepared == null || !prepared.reverse()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PREPARED", "反向准备结果不能为空");
        }
        SourceRecord source = lockSource(prepared.productionSourceBusinessId());
        DemandRecord demand = requireDemand(source);
        ConsumptionRepository.ConsumptionRecord consumed = activeConsumption(prepared.delivery())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CONSUME_NOT_FOUND",
                        "该交付没有可冲销的消费事实"));
        int remaining = remainingOf(consumed);
        if (remaining < prepared.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CONSUME_INSUFFICIENT",
                    "可冲销数量不足：需要 " + prepared.quantity() + "，剩余有效消费 " + remaining);
        }

        Instant now = clock.instant();
        consumptions.insertConsumption(new ConsumptionRepository.ConsumptionRecord(
                prepared.consumptionFactBusinessId(), "REVERSE", consumed.consumptionFactBusinessId(),
                prepared.delivery().sourceModule(), prepared.delivery().sourceType(),
                prepared.delivery().sourceBusinessId(), source.productionSourceBusinessId(),
                source.physicalRootSourceBusinessId(), demand.demandBusinessId(),
                demand.productBusinessId(), prepared.quantity(), now, operatorId()));

        applyProjections(demand, source, prepared.quantity(), false);
        return new ConsumptionResult(
                prepared.consumptionFactBusinessId(), source.productionSourceBusinessId(),
                prepared.quantity(), consumptions.consumedQuantityOf(source.productionSourceBusinessId()),
                availableToConsume(source, demand), true);
    }

    private java.util.Optional<ConsumptionRepository.ConsumptionRecord> activeConsumption(
            com.yumi.production.api.ProductionLinkRef delivery) {
        return consumptions.findConsumptions(
                        delivery.sourceModule(), delivery.sourceType(), delivery.sourceBusinessId())
                .stream()
                .filter(row -> "CONSUME".equals(row.effectType()))
                .filter(row -> remainingOf(row) > 0)
                .reduce((first, second) -> second);
    }

    private int remainingOf(ConsumptionRepository.ConsumptionRecord consumed) {
        return consumed.quantity() - consumptions.reversedQuantityOf(consumed.consumptionFactBusinessId());
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

    /** 可消费数量 = 目标工序就绪 − 已消费 − 已移交线下。 */
    private int availableToConsume(SourceRecord source, DemandRecord demand) {
        var projection = production.findDemandProjection(demand.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "需求数量投影缺失"));
        // 目标就绪数量在消费时已从投影中扣除，这里只再扣已移交部分，避免同一数量被扣两次
        return projection.targetReadyQuantity()
                - consumptions.transferredQuantityOf(source.productionSourceBusinessId());
    }

    private void applyProjections(
            DemandRecord demand, SourceRecord source, int quantity, boolean consume) {
        var demandProjection = production.findDemandProjection(demand.demandBusinessId()).orElseThrow();
        int targetReady = demandProjection.targetReadyQuantity() - quantity;
        int fulfilled = demandProjection.fulfilledQuantity() + quantity;
        if (!consume) {
            targetReady = demandProjection.targetReadyQuantity() + quantity;
            fulfilled = demandProjection.fulfilledQuantity() - quantity;
        }
        if (targetReady < 0 || fulfilled < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                    "消费导致数量投影为负，拒绝提交");
        }
        production.insertDemandProjection(new com.yumi.production.application.port.QuantityProjections
                .DemandProjection(
                        demand.demandBusinessId(), demandProjection.requiredQuantity(),
                        demandProjection.pendingWorkQuantity(), demandProjection.inProcessQuantity(),
                        targetReady, fulfilled, demandProjection.fulfillmentGapQuantity(),
                        demandProjection.rowVersion() + 1));

        var physical = production.findPhysicalProjection(source.physicalRootSourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "物理投影缺失"));
        int inSystem = physical.inSystemQuantity() - (consume ? quantity : -quantity);
        int consumed = physical.consumedQuantity() + (consume ? quantity : -quantity);
        if (inSystem < 0 || consumed < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                    "消费导致物理投影为负，拒绝提交");
        }
        production.insertPhysicalProjection(new com.yumi.production.application.port.QuantityProjections
                .PhysicalProjection(
                        physical.physicalRootSourceBusinessId(),
                        physical.cumulativeMakingCreatedQuantity(), inSystem, consumed,
                        physical.destroyedQuantity(), physical.offlineInventoryQuantity(),
                        physical.rowVersion() + 1));
    }

    private String operatorId() {
        return currentActor.requireCurrentActor().accountBusinessId().value();
    }
}
