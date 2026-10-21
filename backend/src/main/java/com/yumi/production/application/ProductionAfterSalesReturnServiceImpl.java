package com.yumi.production.application;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.PreparedReturnDisposition;
import com.yumi.production.api.PreparedReturnReentry;
import com.yumi.production.api.ProductionAfterSalesReturnParticipant;
import com.yumi.production.api.RegisterReturnCommand;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionCommand;
import com.yumi.production.api.ReturnDispositionResult;
import com.yumi.production.api.ReturnDispositionType;
import com.yumi.production.api.ReturnReentryResult;
import com.yumi.production.application.port.DispositionRepository;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.QuantityProjections;
import com.yumi.production.application.port.ReturnedProductRepository;
import com.yumi.production.application.port.SourceRecord;
import com.yumi.production.application.port.VerificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 售后退回制品参与实现（基线 11 §7.11、§7.12）。
 *
 * <p>退回重入只移动物理根余额，不增加制作量；池内处置不创建任务、劳动或材料事实。
 */
public class ProductionAfterSalesReturnServiceImpl
        implements ProductionAfterSalesReturnParticipant {

    private static final String RETURN_SOURCE_TYPE = "RETURNED_PRODUCT_POOL";
    private static final String POOL_STATUS_AVAILABLE = "AVAILABLE";
    private static final String POOL_STATUS_DISPOSED = "DISPOSED";

    private final ReturnedProductRepository returns;
    private final ProductionRepository production;
    private final VerificationRepository verifications;
    private final DispositionRepository dispositions;
    private final CurrentActorProvider currentActor;
    private final Clock clock;

    public ProductionAfterSalesReturnServiceImpl(
            ReturnedProductRepository returns,
            ProductionRepository production,
            VerificationRepository verifications,
            DispositionRepository dispositions,
            CurrentActorProvider currentActor,
            Clock clock) {
        this.returns = returns;
        this.production = production;
        this.verifications = verifications;
        this.dispositions = dispositions;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedReturnReentry prepareRegisterReturn(RegisterReturnCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_RETURN", "退回命令不能为空");
        }
        var existing = returns.findByReceiptItem(command.afterSalesReturnReceiptItemBusinessId());
        if (existing.isPresent()) {
            var source = existing.get();
            if (source.originalQuantity() != command.quantity()) {
                throw new ApiException(
                        ApiErrorType.IDEMPOTENCY_CONFLICT, "AFTER_SALES_RETURN_CONFLICT",
                        "同一接收明细的退回数量不一致");
            }
            return prepared(source, null);
        }

        PriorSource prior = lockPriorSource(command);
        QuantityProjections.PhysicalProjection physical = returns.lockPhysicalRoot(prior.physicalRoot());
        if (physical.consumedQuantity() < command.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_RETURN_QUANTITY_EXCEEDED",
                    "可退回数量不足：物理根已消费 " + physical.consumedQuantity());
        }
        return new PreparedReturnReentry(
                "RRF-" + UUID.randomUUID(), "RPS-" + UUID.randomUUID(),
                command.afterSalesReturnReceiptItemBusinessId(), prior.physicalRoot(),
                prior.productSnapshotRef(), prior.schemeSnapshotRef(), prior.stageCode(),
                command.priorDeliverySourceType(), command.priorProductionSourceBusinessId(),
                command.priorReturnedSourceBusinessId(), command.priorConsumptionFactBusinessId(),
                command.quantity());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnReentryResult commitRegisterReturn(PreparedReturnReentry prepared) {
        if (prepared == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PREPARED", "退回准备结果不能为空");
        }
        var existing = returns.findByReceiptItem(prepared.afterSalesReturnReceiptItemBusinessId());
        if (existing.isPresent()) {
            var source = existing.get();
            return new ReturnReentryResult(
                    source.returnedSourceBusinessId(), prepared.reentryFactBusinessId(),
                    source.originalQuantity(), source.availableQuantity());
        }
        RegisterReturnCommand command = new RegisterReturnCommand(
                prepared.afterSalesReturnReceiptItemBusinessId(), prepared.priorDeliverySourceType(),
                prepared.priorProductionSourceBusinessId(), prepared.priorReturnedSourceBusinessId(),
                prepared.priorConsumptionFactBusinessId(), prepared.quantity());
        lockPriorSource(command);
        QuantityProjections.PhysicalProjection physical = returns.lockPhysicalRoot(
                prepared.physicalRootSourceBusinessId());
        if (physical.consumedQuantity() < prepared.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_RETURN_QUANTITY_EXCEEDED",
                    "可退回数量不足：物理根已消费 " + physical.consumedQuantity());
        }

        Instant now = clock.instant();
        String operatorId = operatorId();
        returns.insertReturnedSource(new ReturnedProductRepository.ReturnedSourceRecord(
                prepared.returnedSourceBusinessId(),
                prepared.afterSalesReturnReceiptItemBusinessId(),
                prepared.physicalRootSourceBusinessId(), prepared.priorDeliverySourceType(),
                prepared.priorProductionSourceBusinessId(), prepared.priorReturnedSourceBusinessId(),
                prepared.priorConsumptionFactBusinessId(), prepared.productSnapshotRef(),
                prepared.schemeSnapshotRef(), prepared.returnedStageCode(), prepared.quantity(),
                prepared.quantity(), 0, 0, POOL_STATUS_AVAILABLE, 1L, now));
        returns.insertReentryFact(new ReturnedProductRepository.ReentryFact(
                prepared.reentryFactBusinessId(), prepared.returnedSourceBusinessId(),
                prepared.afterSalesReturnReceiptItemBusinessId(), prepared.physicalRootSourceBusinessId(),
                prepared.priorDeliverySourceType(), prepared.priorProductionSourceBusinessId(),
                prepared.priorReturnedSourceBusinessId(), prepared.priorConsumptionFactBusinessId(),
                prepared.quantity(), now, operatorId));
        production.insertPhysicalProjection(new QuantityProjections.PhysicalProjection(
                prepared.physicalRootSourceBusinessId(),
                physical.cumulativeMakingCreatedQuantity(),
                physical.inSystemQuantity() + prepared.quantity(),
                physical.consumedQuantity() - prepared.quantity(),
                physical.destroyedQuantity(), physical.offlineInventoryQuantity(),
                physical.rowVersion() + 1));

        return new ReturnReentryResult(
                prepared.returnedSourceBusinessId(), prepared.reentryFactBusinessId(),
                prepared.quantity(), prepared.quantity());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedReturnDisposition prepareDisposition(ReturnDispositionCommand command) {
        ReturnedProductRepository.ReturnedSourceRecord source =
                lockAndValidateDisposition(command);
        validateEvidence(source, command);
        String dispositionId = "RDF-" + UUID.randomUUID();
        boolean terminates = command.dispositionType() != ReturnDispositionType.RETURN_TO_CUSTOMER;
        return new PreparedReturnDisposition(
                dispositionId,
                terminates ? "TEX-" + UUID.randomUUID() : null,
                command.dispositionType() == ReturnDispositionType.RETURN_TO_CUSTOMER
                        ? "RPC-" + UUID.randomUUID() : null,
                command.physicalDestination() == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY
                        ? "RPT-" + UUID.randomUUID() : null,
                command.dispositionType() == ReturnDispositionType.SCRAPPED
                        ? "RSP-" + UUID.randomUUID() : null,
                source.returnedSourceBusinessId(), source.physicalRootSourceBusinessId(),
                command.dispositionType(), command.physicalDestination(), command.quantity());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int availablePoolQuantity(String returnedSourceBusinessId) {
        return returns.findReturnedSource(returnedSourceBusinessId)
                .map(ReturnedProductRepository.ReturnedSourceRecord::availableQuantity)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_RETURNED_SOURCE_NOT_FOUND",
                        "退回制品来源不存在"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnDispositionResult commitDisposition(
            ReturnDispositionCommand command, PreparedReturnDisposition prepared) {
        if (command == null || prepared == null || command.quantity() != prepared.quantity()
                || command.dispositionType() != prepared.dispositionType()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "MISSING_PREPARED", "处置命令与准备结果不一致");
        }
        ReturnedProductRepository.ReturnedSourceRecord source =
                lockAndValidateDisposition(command);
        validateEvidence(source, command);

        Instant now = clock.instant();
        String operatorId = operatorId();
        int available = source.availableQuantity() - command.quantity();
        int disposed = source.disposedQuantity() + command.quantity();
        returns.updateReturnedSourceQuantities(
                source.returnedSourceBusinessId(), available, source.routedRepairQuantity(),
                disposed, available == 0 ? POOL_STATUS_DISPOSED : POOL_STATUS_AVAILABLE);

        if (command.dispositionType() == ReturnDispositionType.RETURN_TO_CUSTOMER) {
            returns.insertPoolConsumption(new ReturnedProductRepository.PoolConsumption(
                    prepared.consumptionFactBusinessId(), source.returnedSourceBusinessId(),
                    source.physicalRootSourceBusinessId(), command.consumerModule(),
                    command.consumerType(), command.consumerBusinessId(), command.quantity(),
                    now, operatorId));
        } else {
            insertTerminalExitAndOptionalTransfer(source, command, prepared, now, operatorId);
        }

        returns.insertPoolDisposition(new ReturnedProductRepository.PoolDisposition(
                prepared.dispositionFactBusinessId(), source.returnedSourceBusinessId(),
                source.physicalRootSourceBusinessId(), command.dispositionType(),
                command.physicalDestination(), prepared.terminalExitBusinessId(),
                command.quantity(), prepared.consumptionFactBusinessId(),
                prepared.transferFactBusinessId(), command.dispositionType().name(),
                command.reasonText(), command.businessDate(), now, operatorId));

        QuantityProjections.PhysicalProjection physical = returns.lockPhysicalRoot(
                source.physicalRootSourceBusinessId());
        int inSystem = physical.inSystemQuantity() - command.quantity();
        int consumed = physical.consumedQuantity();
        int destroyed = physical.destroyedQuantity();
        int offline = physical.offlineInventoryQuantity();
        switch (command.dispositionType()) {
            case RETURN_TO_CUSTOMER -> consumed += command.quantity();
            case SCRAPPED -> {
                if (command.physicalDestination() == PhysicalDestination.DESTROYED) {
                    destroyed += command.quantity();
                } else {
                    offline += command.quantity();
                }
            }
            case RELEASE_FROM_OBLIGATION -> offline += command.quantity();
        }
        if (inSystem < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                    "退回制品池处置导致系统内余额为负");
        }
        production.insertPhysicalProjection(new QuantityProjections.PhysicalProjection(
                source.physicalRootSourceBusinessId(),
                physical.cumulativeMakingCreatedQuantity(), inSystem, consumed, destroyed, offline,
                physical.rowVersion() + 1));

        return new ReturnDispositionResult(
                prepared.dispositionFactBusinessId(), prepared.terminalExitBusinessId(),
                prepared.consumptionFactBusinessId(), prepared.transferFactBusinessId(),
                prepared.responsibilityChainBusinessId(), command.quantity(), available);
    }

    private ReturnedProductRepository.ReturnedSourceRecord lockAndValidateDisposition(
            ReturnDispositionCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DISPOSITION", "处置命令不能为空");
        }
        if (command.quantity() <= 0) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DISPOSITION", "处置数量必须大于零");
        }
        if (command.reasonText() == null || command.reasonText().isBlank()
                || command.businessDate() == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "MISSING_DISPOSITION", "处置必须填写原因和业务日期");
        }
        var source = returns.lockReturnedSource(command.returnedSourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_RETURNED_SOURCE_NOT_FOUND",
                        "退回制品来源不存在"));
        if (source.availableQuantity() < command.quantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_RETURN_QUANTITY_EXCEEDED",
                    "退回制品可处置数量不足：可用 " + source.availableQuantity());
        }
        switch (command.dispositionType()) {
            case RETURN_TO_CUSTOMER -> {
                if (command.physicalDestination() != PhysicalDestination.CONSUMED) {
                    throw new ApiException(
                            ApiErrorType.VALIDATION, "AFTER_SALES_RETURN_DISPOSITION_REQUIRED",
                            "原样退还去向必须为已消费");
                }
                if (command.consumerModule() == null || command.consumerType() == null
                        || command.consumerBusinessId() == null) {
                    throw new ApiException(
                            ApiErrorType.VALIDATION, "AFTER_SALES_RETURN_TRACE_REQUIRED",
                            "原样退还必须关联售后交付来源");
                }
            }
            case SCRAPPED -> {
                if (command.physicalDestination() != PhysicalDestination.DESTROYED
                        && command.physicalDestination()
                                != PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY) {
                    throw new ApiException(
                            ApiErrorType.VALIDATION, "AFTER_SALES_RETURN_DISPOSITION_REQUIRED",
                            "报废必须明确实际销毁或转线下");
                }
                if (command.responsibilityType() == null) {
                    throw new ApiException(
                            ApiErrorType.VALIDATION, "AFTER_SALES_RETURN_DISPOSITION_REQUIRED",
                            "池内直接报废必须给出责任或免除结论");
                }
            }
            case RELEASE_FROM_OBLIGATION -> {
                if (command.physicalDestination()
                        != PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY) {
                    throw new ApiException(
                            ApiErrorType.VALIDATION, "AFTER_SALES_RETURN_DISPOSITION_REQUIRED",
                            "非报废义务释放只能转线下");
                }
            }
        }
        if (command.physicalDestination() == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY
                && (command.offlineInventoryName() == null || command.offlineInventoryName().isBlank()
                        || command.handedOverBy() == null || command.receivedBy() == null
                        || command.transferredAt() == null)) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "AFTER_SALES_OFFLINE_INVENTORY_IS_TERMINAL",
                    "转线下必须填写接收位置、交接人与接收人");
        }
        return source;
    }

    private void validateEvidence(
            ReturnedProductRepository.ReturnedSourceRecord source, ReturnDispositionCommand command) {
        if (command.dispositionType() != ReturnDispositionType.SCRAPPED) {
            if (command.responsibilityType() != null) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "AFTER_SALES_RETURN_DISPOSITION_REQUIRED",
                        "非报废处置不能记录扣款责任");
            }
            return;
        }
        ResponsibilityType type = command.responsibilityType();
        if (type == ResponsibilityType.WAIVED) {
            if (command.waivedReason() == null || command.waivedReason().isBlank()) {
                throw new ApiException(
                        ApiErrorType.VALIDATION, "AFTER_SALES_RETURN_DISPOSITION_REQUIRED",
                        "责任免除必须填写原因");
            }
            return;
        }
        if (type == ResponsibilityType.EMPLOYEE || type == ResponsibilityType.TEAM) {
            if (command.responsibleSubjectBusinessId() == null
                    || returns.countExecutionEvidence(
                            source.physicalRootSourceBusinessId(),
                            command.responsibleSubjectBusinessId()) == 0) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_RESPONSIBILITY_EVIDENCE_REQUIRED",
                        "所选责任主体在该物理根上没有有效历史作业证据");
            }
        }
    }

    private void insertTerminalExitAndOptionalTransfer(
            ReturnedProductRepository.ReturnedSourceRecord source, ReturnDispositionCommand command,
            PreparedReturnDisposition prepared, Instant now, String operatorId) {
        SourceRecord physicalRoot = production.findSource(source.physicalRootSourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_RETURN_ROOT_REQUIRED",
                        "退回制品的原始物理根不存在"));
        String exitReason = command.dispositionType() == ReturnDispositionType.SCRAPPED
                ? "QUALITY_SCRAP" : "UPSTREAM_RELEASE";
        verifications.insertTerminalExit(new VerificationRepository.TerminalExit(
                prepared.terminalExitBusinessId(), source.returnedSourceBusinessId(),
                source.physicalRootSourceBusinessId(), physicalRoot.demandBusinessId(), null,
                exitReason, command.physicalDestination(), command.quantity(), now));

        if (command.dispositionType() == ReturnDispositionType.SCRAPPED) {
            verifications.insertResponsibility(new VerificationRepository.ResponsibilityFact(
                    prepared.responsibilityChainBusinessId(),
                    prepared.dispositionFactBusinessId(), prepared.terminalExitBusinessId(),
                    command.responsibilityType().name(), command.responsibleSubjectBusinessId(),
                    command.waivedReason(), command.quantity(), command.businessDate(), now));
            dispositions.insertRevision(new DispositionRepository.RevisionRow(
                    "RSV-" + UUID.randomUUID(), prepared.responsibilityChainBusinessId(),
                    prepared.dispositionFactBusinessId(), "APPLY", command.responsibilityType(),
                    command.responsibleSubjectBusinessId(), command.waivedReason(),
                    command.quantity(), command.reasonText(), 0L));
        }
        if (command.physicalDestination() == PhysicalDestination.TRANSFERRED_OFFLINE_INVENTORY) {
            returns.insertPoolTransfer(new ReturnedProductRepository.PoolTransfer(
                    prepared.transferFactBusinessId(), prepared.terminalExitBusinessId(),
                    source.returnedSourceBusinessId(), source.physicalRootSourceBusinessId(),
                    prepared.dispositionFactBusinessId(), command.offlineInventoryName().strip(),
                    command.offlineInventoryNote(), command.handedOverBy().strip(),
                    command.receivedBy().strip(),
                    command.transferredAt(), command.quantity(), now, operatorId));
        }
    }

    private PriorSource lockPriorSource(RegisterReturnCommand command) {
        if ("PRODUCTION_SOURCE".equals(command.priorDeliverySourceType())) {
            SourceRecord source = production.lockSource(command.priorProductionSourceBusinessId());
            return new PriorSource(
                    source.physicalRootSourceBusinessId(), source.productSnapshotRef(),
                    source.schemeSnapshotRef(), source.targetProcess().name());
        }
        var source = returns.lockReturnedSource(command.priorReturnedSourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_RETURNED_SOURCE_NOT_FOUND",
                        "上次交付的退回制品来源不存在"));
        return new PriorSource(
                source.physicalRootSourceBusinessId(), source.productSnapshotRef(),
                source.schemeSnapshotRef(), RETURN_SOURCE_TYPE);
    }

    private static PreparedReturnReentry prepared(
            ReturnedProductRepository.ReturnedSourceRecord source, String reentryFactId) {
        return new PreparedReturnReentry(
                reentryFactId == null ? "RRF-" + UUID.randomUUID() : reentryFactId,
                source.returnedSourceBusinessId(),
                source.afterSalesReturnReceiptItemBusinessId(),
                source.physicalRootSourceBusinessId(), source.productSnapshotRef(),
                source.schemeSnapshotRef(), source.returnedStageCode(),
                source.priorDeliverySourceType(), source.priorProductionSourceBusinessId(),
                source.priorReturnedSourceBusinessId(), source.priorConsumptionFactBusinessId(),
                source.originalQuantity());
    }

    private String operatorId() {
        return currentActor.requireCurrentActor().accountBusinessId().value();
    }

    private record PriorSource(
            String physicalRoot, String productSnapshotRef, String schemeSnapshotRef,
            String stageCode) {
    }
}
