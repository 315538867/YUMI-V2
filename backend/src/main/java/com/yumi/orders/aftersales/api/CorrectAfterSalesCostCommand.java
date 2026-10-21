package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;

/** 更正售后成本：发布旧版本 REVERSE 与新版本 ACCRUE。 */
public record CorrectAfterSalesCostCommand(
        String idempotencyKey,
        String caseBusinessId,
        String sourceBusinessId,
        SettlementMoney amount,
        LocalDate businessDate,
        String reasonText) {

    public CorrectAfterSalesCostCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("售后成本更正必须携带幂等键");
        }
        if (caseBusinessId == null || caseBusinessId.isBlank()
                || sourceBusinessId == null || sourceBusinessId.isBlank()) {
            throw new IllegalArgumentException("售后成本更正必须指定案例与来源");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("售后成本更正金额必须大于零");
        }
        if (businessDate == null || reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("售后成本更正必须填写业务日期和原因");
        }
    }
}
