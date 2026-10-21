package com.yumi.finance.payroll.api;

/** 反向工资支付关联。 */
public record ReversePayrollPaymentLink(
        String linkBusinessId, String financeCashFactBusinessId, String reasonText) {

    public ReversePayrollPaymentLink {
        if (linkBusinessId == null || linkBusinessId.isBlank()) {
            throw new IllegalArgumentException("反向工资支付必须指定原关联");
        }
        if (financeCashFactBusinessId == null || financeCashFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("反向工资支付必须指定反向现金事实");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("反向工资支付必须填写原因");
        }
    }
}
