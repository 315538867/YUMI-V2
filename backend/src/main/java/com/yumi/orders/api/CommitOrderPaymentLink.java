package com.yumi.orders.api;

/**
 * 提交订单支付关联：与调用方现金事实同一事务。
 *
 * @param prepared 收付资格预检结果
 * @param financeCashFactBusinessId 调用方现金事实标识
 */
public record CommitOrderPaymentLink(
        PreparedOrderPayment prepared, String financeCashFactBusinessId) {

    public CommitOrderPaymentLink {
        if (prepared == null) {
            throw new IllegalArgumentException("收付资格预检结果不能为空");
        }
        if (financeCashFactBusinessId == null || financeCashFactBusinessId.isBlank()) {
            throw new IllegalArgumentException("支付关联必须指定现金事实");
        }
    }
}
