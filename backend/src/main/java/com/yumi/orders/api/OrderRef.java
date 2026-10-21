package com.yumi.orders.api;

/** 订单引用。 */
public record OrderRef(
        String orderBusinessId,
        String orderNo,
        OrderStatus status,
        int totalQuantity,
        com.yumi.platform.api.value.SettlementMoney totalAmount,
        long contentVersion,
        long rowVersion) {
}
