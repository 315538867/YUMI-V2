package com.yumi.orders.api;

/**
 * 发货更正命令：只能减少数量或登记退回接收，已确认发货不可直接编辑。
 *
 * @param idempotencyKey 幂等键
 * @param shipmentBusinessId 被更正的发货
 * @param shipmentItemBusinessId 被更正的明细
 * @param quantityDecrease 本次减少数量
 * @param reasonText 更正原因
 */
public record CorrectShipmentCommand(
        String idempotencyKey,
        String shipmentBusinessId,
        String shipmentItemBusinessId,
        int quantityDecrease,
        String reasonText) {

    public CorrectShipmentCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式更正命令必须携带幂等键");
        }
        if (shipmentBusinessId == null || shipmentBusinessId.isBlank()) {
            throw new IllegalArgumentException("更正必须指定发货记录");
        }
        if (shipmentItemBusinessId == null || shipmentItemBusinessId.isBlank()) {
            throw new IllegalArgumentException("更正必须指定发货明细");
        }
        if (quantityDecrease <= 0) {
            throw new IllegalArgumentException("更正数量必须大于 0");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("发货更正必须填写原因");
        }
    }
}
