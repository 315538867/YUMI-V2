package com.yumi.finance.cash.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;
import java.util.List;

/** 售后退款/赔付。 */
public record RefundAfterSalesPaymentCommand(
        String idempotencyKey, String obligationBusinessId, SettlementMoney amount,
        LocalDate businessDate, List<String> voucherFileRefs) {

    public RefundAfterSalesPaymentCommand(
            String idempotencyKey, String obligationBusinessId, SettlementMoney amount,
            LocalDate businessDate) {
        this(idempotencyKey, obligationBusinessId, amount, businessDate, List.of());
    }

    public RefundAfterSalesPaymentCommand {
        voucherFileRefs = List.copyOf(voucherFileRefs == null ? List.of() : voucherFileRefs);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("售后支付必须携带幂等键");
        }
        if (obligationBusinessId == null || obligationBusinessId.isBlank()) {
            throw new IllegalArgumentException("售后支付必须指定金额义务");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("售后支付金额必须大于零");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("售后支付必须填写业务日期");
        }
    }
}
