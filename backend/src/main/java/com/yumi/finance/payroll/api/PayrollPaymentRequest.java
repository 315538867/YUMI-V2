package com.yumi.finance.payroll.api;

import com.yumi.platform.api.value.SettlementMoney;

/** 工资支付资格请求：由 cash 在自身事务内调用。 */
public record PayrollPaymentRequest(
        String resultBusinessId, SettlementMoney amount, String financeCashFactBusinessId) {

    public PayrollPaymentRequest {
        if (resultBusinessId == null || resultBusinessId.isBlank()) {
            throw new IllegalArgumentException("工资支付必须指定员工结果");
        }
        if (amount == null || amount.value().signum() <= 0) {
            throw new IllegalArgumentException("工资支付金额必须大于零");
        }
    }
}
