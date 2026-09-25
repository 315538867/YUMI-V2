package com.yumi.orders.order.internal;

/** 草稿库存计划行（任务 4.8）：表列 + 明细序号（列表展示用）。 */
public record OrderPlanLineRow(
        long id,
        long orderId,
        long orderItemId,
        int lineNo,
        long batchId,
        int quantity) {
}
