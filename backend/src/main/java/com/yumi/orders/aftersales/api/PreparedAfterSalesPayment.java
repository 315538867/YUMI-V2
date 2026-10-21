package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 售后金额义务支付预检结果。 */
public record PreparedAfterSalesPayment(
        String obligationBusinessId,
        SettlementMoney amount,
        SettlementMoney fulfilledBefore,
        SettlementMoney targetAmount) {
}
