package com.yumi.orders.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 已有订单接管命令：一次建立订单、生产需求并完成生产期初初始化。
 *
 * @param originalOrderReference 原订单号/来源说明，只作追溯
 * @param note 接管说明
 */
public record IntakeLegacyOrderCommand(
        String idempotencyKey,
        String customerBusinessId,
        String originalOrderReference,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String note,
        List<LegacyOrderItemRequest> items) {

    public IntakeLegacyOrderCommand {
        items = List.copyOf(items == null ? List.of() : items);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式接管命令必须携带幂等键");
        }
        if (customerBusinessId == null || customerBusinessId.isBlank()) {
            throw new IllegalArgumentException("接管必须指定客户");
        }
        if (orderDate == null || expectedDeliveryDate == null) {
            throw new IllegalArgumentException("接管必须给出订单日期与预计交付日期");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("接管必须至少包含一个订单项");
        }
    }
}
