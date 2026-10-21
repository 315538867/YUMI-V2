package com.yumi.orders.aftersales.api;

import com.yumi.orders.api.RecipientInfo;
import java.time.LocalDateTime;

/** 未修复原样退还：由售后交付命令同事务完成池处置与交付记录。 */
public record ReturnToCustomerCommand(
        String idempotencyKey,
        String receiptItemBusinessId,
        int quantity,
        LocalDateTime deliveredAt,
        RecipientInfo recipient,
        String trackingNo) {

    public ReturnToCustomerCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("原样退还必须携带幂等键");
        }
        if (receiptItemBusinessId == null || receiptItemBusinessId.isBlank()) {
            throw new IllegalArgumentException("原样退还必须指定退回接收明细");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("退还数量必须大于零");
        }
        if (deliveredAt == null) {
            throw new IllegalArgumentException("原样退还必须填写交付时间");
        }
        if (recipient == null) {
            throw new IllegalArgumentException("原样退还必须填写收货信息");
        }
    }
}
