package com.yumi.orders.aftersales.api;

/** 请求把已退回制品转为原物修复。 */
public record RequestRepairCommand(
        String idempotencyKey,
        String obligationBusinessId,
        String receiptItemBusinessId,
        int quantity) {

    public RequestRepairCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("返修申请必须携带幂等键");
        }
        if (obligationBusinessId == null || obligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("返修申请必须指定售后义务");
        }
        if (receiptItemBusinessId == null || receiptItemBusinessId.isBlank()) {
            throw new IllegalArgumentException("返修申请必须指定退回接收明细");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("返修数量必须大于零");
        }
    }
}
