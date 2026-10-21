package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 工资实际部分退回：只能退回已付净额。 */
public record PayrollReturnRequest(
        String resultBusinessId, String originalFinanceCashFactId, SettlementMoney amount) {

    public PayrollReturnRequest {
        if (resultBusinessId == null || resultBusinessId.isBlank()) {
            throw new IllegalArgumentException("工资退回必须指定员工结果");
        }
        if (originalFinanceCashFactId == null || originalFinanceCashFactId.isBlank()) {
            throw new IllegalArgumentException("工资退回必须指定原付款现金事实");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("工资退回金额必须大于零");
        }
    }
}
