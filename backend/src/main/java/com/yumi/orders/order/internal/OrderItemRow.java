package com.yumi.orders.order.internal;

import java.math.BigDecimal;

/**
 * order_items 表一行：当前有效明细值。
 * {@code seamTypeId} 为空即视为不缝边剪袋（E=0 时缝边字段一律为空或 0）。
 */
public record OrderItemRow(
        Long id,
        long orderId,
        int lineNo,
        long productId,
        String productNo,
        String productName,
        int quantity,
        int seamQuantity,
        BigDecimal unitPrice,
        BigDecimal goodsAmount,
        Long seamTypeId,
        String seamTypeName,
        BigDecimal seamUnitCost,
        BigDecimal seamFee,
        BigDecimal seamAmount,
        BigDecimal unitCost,
        BigDecimal goodsCostAmount,
        BigDecimal seamCostAmount,
        String note,
        long version) {
}
