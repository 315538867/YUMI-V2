package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 工资核定结果。 */
public record PayrollConfirmationRef(
        String resultBusinessId,
        String confirmationBusinessId,
        SettlementMoney confirmedAmount,
        SettlementMoney payableAmount) {
}
