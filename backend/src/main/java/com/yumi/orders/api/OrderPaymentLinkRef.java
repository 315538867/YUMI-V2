package com.yumi.orders.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 订单支付关联结果。 */
public record OrderPaymentLinkRef(
        String factBusinessId,
        String orderBusinessId,
        OrderPaymentType paymentType,
        SettlementMoney amount,
        boolean reversal) {
}
