package com.yumi.orders.aftersales.api;

/**
 * 接收退回：服务端按订单项查找最近一次交付明细，并按原消费事实重入退回制品池。
 */
public record ReceiveReturnCommand(
        String idempotencyKey,
        String obligationBusinessId,
        int quantity,
        String conditionCode) {

    public ReceiveReturnCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("退回接收必须携带幂等键");
        }
        if (obligationBusinessId == null || obligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("退回接收必须指定售后义务");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("退回数量必须大于零");
        }
        if (conditionCode == null || conditionCode.isBlank()) {
            throw new IllegalArgumentException("退回接收必须填写品相");
        }
    }
}
