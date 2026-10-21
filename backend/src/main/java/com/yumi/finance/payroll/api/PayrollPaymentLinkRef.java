package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 工资支付关联结果。 */
public record PayrollPaymentLinkRef(
        String linkBusinessId,
        String resultBusinessId,
        SettlementMoney amount,
        SettlementMoney paidNetAmount,
        SettlementMoney remainingAmount,
        boolean reversal) {
}
