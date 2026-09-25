package com.yumi.orders.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 编辑订单草稿入参：除 {@code version} 必填外均可选，未传字段保持原值。
 * {@code items} 传非空列表时整体替换草稿明细（草稿阶段允许增删行；已确认后只能走订单变更）；
 * 明细被替换时原计划行随之失效，未同时传 {@code inventoryPlan} 则一并清空。
 * {@code inventoryPlan} 传非空列表时整体替换草稿库存计划，不占用库存。
 * 仅 {@code DRAFT} 状态可编辑，其他状态返回 {@code STATE_NOT_EDITABLE}。
 */
public record UpdateOrderRequest(
        Long version,
        Long customerId,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        Boolean clearExpectedDeliveryDate,
        String recipientName,
        String recipientPhone,
        String region,
        String address,
        String note,
        BigDecimal discountAmount,
        List<OrderItemRequest> items,
        List<OrderPlanLineRequest> inventoryPlan,
        String reason) {
}
