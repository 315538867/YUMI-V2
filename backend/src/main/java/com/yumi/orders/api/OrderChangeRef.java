package com.yumi.orders.api;

/** 订单变更结果。 */
public record OrderChangeRef(
        String changeBusinessId,
        String orderBusinessId,
        long contentVersion,
        int actionCount,
        com.yumi.platform.api.value.SettlementMoney currentReceivableAmount) {
}
