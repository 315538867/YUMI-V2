package com.yumi.orders.api;

/** 确认订单完成命令：交付与付款均满足且无阻塞义务。 */
public record CompleteOrderCommand(String idempotencyKey, String orderBusinessId) {

    public CompleteOrderCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式完成命令必须携带幂等键");
        }
        if (orderBusinessId == null || orderBusinessId.isBlank()) {
            throw new IllegalArgumentException("完成命令必须指定订单");
        }
    }
}
