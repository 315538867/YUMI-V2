package com.yumi.orders.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 确认发货命令：命令成功即为已确认发货，不建立草稿或长期预留。
 *
 * @param idempotencyKey 幂等键
 * @param orderBusinessId 订单
 * @param items 发货明细；一项明细消费一个生产来源
 * @param trackingNo 物流单号
 * @param shippedAt 发货时间
 */
public record ConfirmShipmentCommand(
        String idempotencyKey,
        String orderBusinessId,
        List<ShipmentItemRequest> items,
        String trackingNo,
        LocalDateTime shippedAt) {

    public ConfirmShipmentCommand {
        items = List.copyOf(items == null ? List.of() : items);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式发货命令必须携带幂等键");
        }
        if (orderBusinessId == null || orderBusinessId.isBlank()) {
            throw new IllegalArgumentException("发货必须指定订单");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("发货必须至少有一条明细");
        }
        if (shippedAt == null) {
            throw new IllegalArgumentException("发货必须填写发货时间");
        }
    }
}
