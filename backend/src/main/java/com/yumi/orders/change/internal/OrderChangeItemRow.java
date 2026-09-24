package com.yumi.orders.change.internal;

import java.math.BigDecimal;

/**
 * order_change_items 表一行：结构化前后值与减单超出处理方案。
 * {@code changeType} 为 {@code ADD}/{@code UPDATE}/{@code REMOVE}；新增行的 {@code orderItemId} 为空。
 */
public record OrderChangeItemRow(
        Long id,
        long changeOrderId,
        Long orderItemId,
        String changeType,
        Integer lineNo,
        Long productId,
        Integer beforeQuantity,
        Integer afterQuantity,
        Integer beforeSeamQuantity,
        Integer afterSeamQuantity,
        BigDecimal beforeUnitPrice,
        BigDecimal afterUnitPrice,
        Long beforeSeamTypeId,
        Long afterSeamTypeId,
        BigDecimal beforeSeamFee,
        BigDecimal afterSeamFee,
        String beforeNote,
        String afterNote,
        String surplusDisposition,
        Integer surplusQuantity,
        String surplusReason) {
}
