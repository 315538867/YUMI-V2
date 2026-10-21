package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 售后成本确认结果。 */
public record AfterSalesCostRef(
        String sourceBusinessId,
        int sourceVersion,
        SettlementMoney amount,
        String operationType) {
}
