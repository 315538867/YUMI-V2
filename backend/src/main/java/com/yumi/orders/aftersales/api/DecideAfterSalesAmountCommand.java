package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 售后退款/赔付金额决定；决定与真实现金支付分离。 */
public record DecideAfterSalesAmountCommand(
        String idempotencyKey,
        String obligationBusinessId,
        SettlementMoney amount,
        String note) {

    public DecideAfterSalesAmountCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("金额决定必须携带幂等键");
        }
        if (obligationBusinessId == null || obligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("金额决定必须指定义务");
        }
        if (amount == null) {
            throw new IllegalArgumentException("金额决定必须填写金额");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("金额决定必须填写说明");
        }
    }
}
