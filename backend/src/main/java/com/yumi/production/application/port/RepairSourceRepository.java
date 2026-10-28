package com.yumi.production.application.port;

import com.yumi.production.api.DemandType;
import com.yumi.production.api.TargetProcess;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 返工来源读写：由核验返工处置生成，返工任务按活动明细占用并完成。
 *
 * <p>来源可安排数量 = 当前可用数量 − 活动任务预留数量（基线 11 §5.2.1、§6.7）。
 * 完成数量只从当前可用数量扣除，未完成数量随活动预留一起释放。
 */
public interface RepairSourceRepository {

    /** 全部仍可安排的返工来源；调用方按工序筛选。 */
    List<AvailableRepairSourceRecord> listAvailable();

    Optional<RepairSourceRecord> find(String repairSourceBusinessId);

    RepairSourceRecord lock(String repairSourceBusinessId);

    void reserve(String repairSourceBusinessId, int quantity);

    void release(String repairSourceBusinessId, int quantity);

    /** 核验结账：释放本次活动预留，已完成数量从可用数量扣除。 */
    void complete(String repairSourceBusinessId, int plannedQuantity, int completedQuantity);

    void recordFact(
            String repairSourceBusinessId, String operationType, int quantity,
            String taskBusinessId, String taskItemBusinessId, Instant occurredAt, String operatorId);

    /** 返工来源读模型：来源、需求与履约生产来源合并。 */
    record AvailableRepairSourceRecord(
            String repairSourceBusinessId, String originType, String demandBusinessId,
            String productionSourceBusinessId, DemandType demandType, String productBusinessId,
            String schemeBusinessId, TargetProcess targetProcess, TargetProcess repairProcessCode,
            int originalQuantity, int availableQuantity, int reservedQuantity, int completedQuantity) {
    }

    /** 返工来源当前记录。 */
    record RepairSourceRecord(
            String repairSourceBusinessId, String originType, String demandBusinessId,
            String productionSourceBusinessId, String physicalRootSourceBusinessId,
            String repairProcessCode, int originalQuantity, int availableQuantity,
            int reservedQuantity, int completedQuantity, String status) {
    }
}
