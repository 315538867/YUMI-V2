package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 工资退回预检结果。 */
public record PreparedPayrollReturn(
        String resultBusinessId, SettlementMoney amount, SettlementMoney paidBefore) {
}
