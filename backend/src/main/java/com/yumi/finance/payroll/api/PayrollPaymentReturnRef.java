package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 工资实际退回结果。 */
public record PayrollPaymentReturnRef(
        String resultBusinessId, SettlementMoney amount, SettlementMoney paidNetAmount) {
}
