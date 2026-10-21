package com.yumi.orders.aftersales.api;

/** 反向售后支付关联。 */
public record ReverseAfterSalesPaymentLink(
        String factBusinessId, String financeCashFactBusinessId, String reasonText) {

    public ReverseAfterSalesPaymentLink {
        if (factBusinessId == null || factBusinessId.isBlank()
                || financeCashFactBusinessId == null || financeCashFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("反向售后支付必须指定原关联和反向现金事实");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("反向售后支付必须填写原因");
        }
    }
}
