package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.Instant;
import java.time.LocalDate;

/** 售后费用来源视图：费用不是现金。 */
public record AfterSalesCostView(
        String sourceBusinessId, String caseBusinessId, String orderBusinessId,
        String costCategory, SettlementMoney amount, LocalDate businessDate,
        String operationType, String reasonText, Instant occurredAt) {
}
