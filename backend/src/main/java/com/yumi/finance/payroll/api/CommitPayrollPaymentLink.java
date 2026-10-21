package com.yumi.finance.payroll.api;

/** 提交工资支付关联。 */
public record CommitPayrollPaymentLink(
        PreparedPayrollPayment prepared, String financeCashFactBusinessId) {

    public CommitPayrollPaymentLink {
        if (prepared == null) {
            throw new IllegalArgumentException("工资支付预检结果不能为空");
        }
        if (financeCashFactBusinessId == null || financeCashFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("工资支付关联必须指定现金事实");
        }
    }
}
