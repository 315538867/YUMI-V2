package com.yumi.orders.api;

import java.util.List;

/**
 * 订单变更命令。
 *
 * @param idempotencyKey 幂等键
 * @param orderBusinessId 订单标识
 * @param expectedContentVersion 期望的订单内容版本；不匹配即并发冲突
 * @param actions 变更动作
 * @param reasonText 变更原因
 */
public record ChangeConfirmedOrderCommand(
        String idempotencyKey,
        String orderBusinessId,
        long expectedContentVersion,
        List<OrderChangeAction> actions,
        String reasonText) {

    public ChangeConfirmedOrderCommand {
        actions = List.copyOf(actions == null ? List.of() : actions);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式变更命令必须携带幂等键");
        }
        if (orderBusinessId == null || orderBusinessId.isBlank()) {
            throw new IllegalArgumentException("订单标识不能为空");
        }
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("变更命令必须至少包含一个动作");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("变更必须填写原因");
        }
    }
}
