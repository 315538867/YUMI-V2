package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 工资支付资格预检结果；只在当前事务内有效。 */
public record PreparedPayrollPayment(
        String resultBusinessId,
        String employeeBusinessId,
        SettlementMoney amount,
        SettlementMoney paidBefore,
        SettlementMoney payableAmount) {
}
