package com.yumi.orders.change;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单变更明细入参（任务 3.7/3.8）：三种路径共用一条记录。
 * - 新增行：{@code orderItemId} 为空、{@code productId} 必填；
 * - 改单行：{@code orderItemId} 非空，只提交要改的字段（未提交字段沿用当前有效值）；
 * - 移除行：{@code orderItemId} 非空且 {@code remove=true}，按数量归零处理（保留行与历史）。
 * 减单后若超出在制/合格数量，必须提交 {@code surplusDisposition}（{@code FINISH_TO_SURPLUS}/{@code SCRAP}）、
 * {@code surplusQuantity} 与 {@code surplusReason}。
 */
public record OrderChangeItemRequest(
        Long orderItemId,
        Boolean remove,
        Long productId,
        Integer quantity,
        Integer seamQuantity,
        BigDecimal unitPrice,
        Long seamTypeId,
        BigDecimal seamFee,
        String note,
        String surplusDisposition,
        Integer surplusQuantity,
        String surplusReason) {
}
