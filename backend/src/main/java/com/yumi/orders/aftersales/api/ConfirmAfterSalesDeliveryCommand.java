package com.yumi.orders.aftersales.api;

import com.yumi.orders.api.RecipientInfo;
import java.time.LocalDateTime;

/** 返修完成或补发完成后的售后交付；消费生产来源并交付客户。 */
public record ConfirmAfterSalesDeliveryCommand(
        String idempotencyKey,
        String obligationBusinessId,
        int quantity,
        String productionSourceBusinessId,
        LocalDateTime deliveredAt,
        RecipientInfo recipient,
        String trackingNo) {

    public ConfirmAfterSalesDeliveryCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("售后交付必须携带幂等键");
        }
        if (obligationBusinessId == null || obligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("售后交付必须指定义务");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("售后交付数量必须大于零");
        }
        if (productionSourceBusinessId == null || productionSourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("售后交付必须指定生产来源");
        }
        if (deliveredAt == null || recipient == null) {
            throw new IllegalArgumentException("售后交付必须填写时间和收货信息");
        }
    }
}
