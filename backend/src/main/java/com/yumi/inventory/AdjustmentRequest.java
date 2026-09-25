package com.yumi.inventory;

/** 盘点调整入参（任务 4.4）：提交实际数量、原因与备注，由服务端按差异生成盘盈/盘亏流水。 */
public record AdjustmentRequest(
        Long batchId,
        Integer actualQuantity,
        String reason,
        String note) {
}
