package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.Instant;

/** 售后金额决定结果。 */
public record AfterSalesAmountDecisionRef(
        String decisionBusinessId,
        String obligationBusinessId,
        SettlementMoney amount,
        Instant decidedAt) {
}
