package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 售后支付关联结果。 */
public record AfterSalesPaymentLinkRef(
        String factBusinessId,
        String obligationBusinessId,
        SettlementMoney amount,
        SettlementMoney fulfilledAmount,
        boolean reversal) {
}
