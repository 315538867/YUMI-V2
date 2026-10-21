package com.yumi.finance.cash.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;
import java.util.List;

/** 订单收款。 */
public record ReceiveOrderPaymentCommand(
        String idempotencyKey, String orderBusinessId, SettlementMoney amount,
        LocalDate businessDate, List<String> voucherFileRefs) {

    public ReceiveOrderPaymentCommand(
            String idempotencyKey, String orderBusinessId, SettlementMoney amount,
            LocalDate businessDate) {
        this(idempotencyKey, orderBusinessId, amount, businessDate, List.of());
    }

    public ReceiveOrderPaymentCommand {
        voucherFileRefs = List.copyOf(voucherFileRefs == null ? List.of() : voucherFileRefs);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("订单收款必须携带幂等键");
        }
        if (orderBusinessId == null || orderBusinessId.isBlank()) {
            throw new IllegalArgumentException("订单收款必须指定订单");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("收款金额必须大于零");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("收款必须填写业务日期");
        }
    }
}
