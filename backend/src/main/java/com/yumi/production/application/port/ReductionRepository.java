package com.yumi.production.application.port;

import java.util.List;

/** 任务缩减持久化端口（只动未核验部分）。 */
public interface ReductionRepository {

    /** 该来源尚未核验的明细，按任务、明细顺序稳定排序。 */
    List<PendingItem> findPendingItems(String productionSourceBusinessId);

    void updateItemPlannedQuantity(String taskItemBusinessId, int plannedQuantity, int cancelledQuantity);

    void updateTaskTotals(String taskBusinessId, int plannedQuantity, long standardSeconds);

    void cancelTask(String taskBusinessId);

    void releaseReservation(String productionSourceBusinessId, int quantity);

    void releaseHold(String taskItemBusinessId, int remainingQuantity);

    /** 未核验明细。 */
    record PendingItem(
            String taskItemBusinessId, String taskBusinessId, int plannedQuantity,
            int itemStandardMinutes, String productBusinessId, String demandType) {
    }
}
