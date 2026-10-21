package com.yumi.orders.aftersales.api;

/** 提交售后支付关联。 */
public record CommitAfterSalesPaymentLink(
        PreparedAfterSalesPayment prepared, String financeCashFactBusinessId) {

    public CommitAfterSalesPaymentLink {
        if (prepared == null) {
            throw new IllegalArgumentException("售后支付预检结果不能为空");
        }
        if (financeCashFactBusinessId == null || financeCashFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("售后支付必须指定现金事实");
        }
    }
}
