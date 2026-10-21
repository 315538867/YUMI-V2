package com.yumi.production.application.port;

import com.yumi.production.api.DemandType;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.TaskStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 核验事实持久化端口（执行、质量、缺陷、处置、退出、责任、劳动与材料来源）。 */
public interface VerificationRepository {

    Optional<ItemVerificationRecord> findItemVerification(String taskItemBusinessId);

    void insertItemVerification(ItemVerificationRecord record);

    /**
     * 覆盖式更新某明细的核验行。
     *
     * <p>每条明细只保留一条“当前核验”；更正历史由更正命令批次与各事实的 is_valid 标记保存，
     * 因此这里不能插入第二条核验行。
     */
    void updateItemVerification(ItemVerificationRecord record);

    void insertExecution(ExecutionFact fact);

    void insertQuality(QualityFact fact);

    void insertDefect(DefectFact fact);

    void insertDisposition(DispositionFact fact);

    void insertTerminalExit(TerminalExit exit);

    void insertResponsibility(ResponsibilityFact fact);

    /** 发布验收报废责任 APPLY 修订，供工资责任 Feed 消费。 */
    void insertResponsibilityRevision(
            String revisionBusinessId, String responsibilityChainBusinessId,
            String dispositionFactBusinessId, String responsibilityType,
            String responsibleSubjectBusinessId, String waivedReason, int quantity,
            java.time.LocalDate businessDate, String reasonText,
            java.time.Instant occurredAt, String operatorId);

    void insertOtherTaskVerification(OtherTaskVerification fact);

    Optional<OtherTaskVerification> findOtherTaskVerification(String taskBusinessId);

    void insertLaborSource(LaborSource source);

    void insertMaterialCostSource(MaterialCostSource source);

    void updateTaskStatus(String taskBusinessId, TaskStatus status, long rowVersion);

    void updateTaskItemVerification(
            String taskItemBusinessId, int completed, int unfinished, int qualified, int rework, int scrapped,
            int destroyed, int scrapOffline, String verificationStatus);

    void updateDemandProjection(
            String demandBusinessId, int pending, int inProcess, int targetReady, int gap);

    void updateStageProjection(
            String productionSourceBusinessId, String stageCode, int availableDelta, int reservedDelta);

    Optional<DefectReasonRecord> findDefectReason(String code);

    /** 作废某明细的全部有效事实（更正时先反向，再写替代事实）。 */
    void invalidateFactsOfItem(String taskItemBusinessId);

    /** 作废某任务的劳动来源。 */
    void invalidateLaborSourcesOfTask(String taskBusinessId);

    /** 读取某明细的有效劳动来源数量合计（用于反向项数量）。 */
    int sumLaborQuantityOfTask(String taskBusinessId);

    /** 读取某明细的有效材料来源数量。 */
    int sumMaterialQuantityOfItem(String taskItemBusinessId);

    /** 读取某明细报废责任数量合计（用于反向项数量）。 */
    int sumResponsibilityQuantityOfItem(String taskItemBusinessId);

    /** 返工来源：由内部缺陷处置生成，不增加累计制作量。 */
    void insertRepairSource(RepairSourceRecord record);

    void insertRepairSourceFact(
            String repairSourceBusinessId, String operationType, int quantity,
            String taskBusinessId, String taskItemBusinessId, Instant occurredAt, String operatorId);

    /** 返工来源记录。 */
    record RepairSourceRecord(
            String repairSourceBusinessId, String originType, String originModule, String originTypeCode,
            String originBusinessId, String demandBusinessId, String productionSourceBusinessId,
            String physicalRootSourceBusinessId, String originalDefectFactBusinessId,
            String repairProcessCode, int originalQuantity) {
    }

    /** 明细核验事实。 */
    record ItemVerificationRecord(
            String taskItemBusinessId, String taskBusinessId, long assignmentVersion, int plannedQuantity,
            int unfinishedQuantity, int completedQuantity, int qualifiedQuantity, int reworkQuantity,
            int scrappedQuantity, int destroyedQuantity, int scrapOfflineQuantity, LocalDate taskWorkDate,
            Instant verifiedAt, String operatorId, String resultNote) {
    }

    /** 生产执行事实。 */
    record ExecutionFact(
            String factBusinessId, String taskBusinessId, String taskItemBusinessId,
            String demandBusinessId, DemandType demandType, String productionSourceBusinessId,
            String physicalRootSourceBusinessId, String productBusinessId, String productSnapshotRef,
            String schemeBusinessId, String schemeSnapshotRef, String processCode, String subjectType,
            String subjectBusinessId, int quantity, LocalDate taskWorkDate, Instant occurredAt) {
    }

    /** 质量事实。 */
    record QualityFact(
            String factBusinessId, String taskItemBusinessId, String qualityCode, int quantity,
            Instant occurredAt) {
    }

    /** 缺陷事实。 */
    record DefectFact(
            String factBusinessId, String taskItemBusinessId, String defectReasonCode,
            String defectReasonName, String defectReasonText, int quantity, Instant occurredAt) {
    }

    /** 处置事实。 */
    record DispositionFact(
            String factBusinessId, String defectFactBusinessId, String taskItemBusinessId,
            String dispositionType, PhysicalDestination physicalDestination, int quantity,
            String terminalExitBusinessId, String repairSourceBusinessId, Instant occurredAt) {
    }

    /** 终态退出。 */
    record TerminalExit(
            String terminalExitBusinessId, String productionSourceBusinessId,
            String physicalRootSourceBusinessId, String demandBusinessId, String taskItemBusinessId,
            String exitReason, PhysicalDestination physicalDestination, int quantity, Instant occurredAt) {
    }

    /** 责任事实。 */
    record ResponsibilityFact(
            String responsibilityChainBusinessId, String dispositionFactBusinessId,
            String terminalExitBusinessId, String responsibilityType,
            String responsibleSubjectBusinessId, String waivedReason, int quantity,
            LocalDate businessDate, Instant occurredAt) {
    }

    /** 其他任务核验事实。 */
    record OtherTaskVerification(
            String factBusinessId, String taskBusinessId, long assignmentVersion,
            String assessmentBusinessId, Integer plannedQuantity, Integer completedQuantity,
            Integer unfinishedQuantity, String resultNote, Instant verifiedAt, LocalDate taskWorkDate,
            String operatorId) {
    }

    /** 劳动来源。 */
    record LaborSource(
            String laborSourceBusinessId, String taskBusinessId, String taskItemBusinessId,
            String demandBusinessId, String productionSourceBusinessId, String subjectType,
            String subjectBusinessId, String taskType, String processCode, Integer quantity,
            java.math.BigDecimal unitLaborAmount, LocalDate businessDate, Instant occurredAt,
            String effectiveStatus) {
    }

    /** 材料成本来源（不含金额）。 */
    record MaterialCostSource(
            String materialSourceBusinessId, String taskBusinessId, String taskItemBusinessId,
            String demandBusinessId, String productionSourceBusinessId,
            String physicalRootSourceBusinessId, String productBusinessId, String productSnapshotRef,
            String schemeBusinessId, String schemeSnapshotRef, String componentCode,
            String processCode, int quantity, LocalDate businessDate, Instant occurredAt,
            String effectiveStatus) {
    }

    /** 缺陷原因。 */
    record DefectReasonRecord(String code, String name, String status, boolean other) {
    }
}
