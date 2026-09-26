package com.yumi.production.task.internal;

import java.math.BigDecimal;

/** production_task_items 一行：最小事实边界，含创建时冻结的标准分钟与能力、时间快照。 */
public record ProductionTaskItemRow(
        Long id,
        long taskId,
        int itemNo,
        long orderId,
        long orderItemId,
        long productId,
        String productNo,
        String productName,
        String node,
        int plannedQuantity,
        String sourceType,
        long sourceId,
        int standardMinutes,
        long estimatedMinutes,
        BigDecimal makingEffectiveHourRate,
        BigDecimal workdayHours,
        int moldQuantity,
        int dailyBatchLimit,
        String status,
        String cancelledBy,
        String cancelReason,
        long version) {

    /** 每日最大产能 = 模具数量 × 每日批次数（读时派生，不落库）。 */
    public int dailyMaxCapacity() {
        return moldQuantity * dailyBatchLimit;
    }

    /** 正常来源（订单需求或同工序报废回转）：只有它们参与产品日产能与正常工时。 */
    public boolean isNormalSource() {
        return "ORDER".equals(sourceType) || "QUANTITY_RETURN".equals(sourceType);
    }
}
