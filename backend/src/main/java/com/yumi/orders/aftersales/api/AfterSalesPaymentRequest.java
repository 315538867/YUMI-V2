package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 售后金额义务支付资格请求（cash 调用）。 */
public record AfterSalesPaymentRequest(
        String obligationBusinessId, SettlementMoney amount) {

    public AfterSalesPaymentRequest {
        if (obligationBusinessId == null || obligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("售后支付必须指定金额义务");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("售后支付金额必须大于零");
        }
    }
}
