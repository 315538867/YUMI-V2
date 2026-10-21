package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 工资调整结果。 */
public record PayrollAdjustmentRef(
        String resultBusinessId,
        String adjustmentBusinessId,
        SettlementMoney payableAmount) {
}
