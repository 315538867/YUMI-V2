package com.yumi.orders.api;

/**
 * 发货明细：一条明细消费一个生产来源，数量必须不超过该来源可交付余额。
 *
 * @param agreementGroupBusinessId 交付约定组
 * @param productionSourceBusinessId 被消费的生产来源
 * @param quantity 本次发货数量
 */
public record ShipmentItemRequest(
        String agreementGroupBusinessId,
        String productionSourceBusinessId,
        int quantity) {

    public ShipmentItemRequest {
        if (agreementGroupBusinessId == null || agreementGroupBusinessId.isBlank()) {
            throw new IllegalArgumentException("发货明细必须指向交付约定组");
        }
        if (productionSourceBusinessId == null || productionSourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("发货明细必须指定生产来源");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("发货数量必须大于 0");
        }
    }
}
