package com.yumi.finance.cash.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;
import java.util.List;

/** 工资实际部分退回：关联原付款现金事实与员工分配。 */
public record ReturnPayrollPaymentCommand(
        String idempotencyKey,
        String originalCashFactBusinessId,
        String resultBusinessId,
        SettlementMoney amount,
        LocalDate businessDate,
        String reasonText,
        List<String> voucherFileRefs) {

    public ReturnPayrollPaymentCommand {
        voucherFileRefs = List.copyOf(voucherFileRefs == null ? List.of() : voucherFileRefs);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("工资退回必须携带幂等键");
        }
        if (originalCashFactBusinessId == null || originalCashFactBusinessId.isBlank()
                || resultBusinessId == null || resultBusinessId.isBlank()) {
            throw new IllegalArgumentException("工资退回必须指定原付款和员工结果");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("工资退回金额必须大于零");
        }
        if (businessDate == null || reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("工资退回必须填写业务日期和原因");
        }
    }
}
