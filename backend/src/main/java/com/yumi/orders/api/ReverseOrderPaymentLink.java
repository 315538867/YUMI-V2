package com.yumi.orders.api;

/**
 * 反向订单支付关联：现金冲销或真实退回冲销在同一事务调用。
 */
public record ReverseOrderPaymentLink(
        String paymentLinkFactBusinessId, String financeCashFactBusinessId, String reasonText) {

    public ReverseOrderPaymentLink(String paymentLinkFactBusinessId, String reasonText) {
        this(paymentLinkFactBusinessId, null, reasonText);
    }

    public ReverseOrderPaymentLink {
        if (paymentLinkFactBusinessId == null || paymentLinkFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("反向支付关联必须指定原关联事实");
        }
        if (reasonText == null || reasonText.isBlank()) {
            throw new IllegalArgumentException("反向支付关联必须填写原因");
        }
    }
}
