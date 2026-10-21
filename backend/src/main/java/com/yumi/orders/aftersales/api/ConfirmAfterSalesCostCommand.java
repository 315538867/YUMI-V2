package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;

/** 确认工作室承担的售后成本。 */
public record ConfirmAfterSalesCostCommand(
        String idempotencyKey,
        String caseBusinessId,
        String costCategory,
        SettlementMoney amount,
        LocalDate businessDate,
        String reasonText) {

    public ConfirmAfterSalesCostCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("售后成本确认必须携带幂等键");
        }
        if (caseBusinessId == null || caseBusinessId.isBlank()) {
            throw new IllegalArgumentException("售后成本必须指定案例");
        }
        if (costCategory == null || costCategory.isBlank()) {
            throw new IllegalArgumentException("售后成本必须指定分类");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("售后成本金额必须大于零");
        }
        if (businessDate == null || reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("售后成本必须填写业务日期和原因");
        }
    }
}
