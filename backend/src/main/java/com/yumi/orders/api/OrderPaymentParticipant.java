package com.yumi.orders.api;

/**
 * 订单支付参与接口（finance 在同一事务内调用）。
 *
 * <p>规则来源：`docs/一期/12-订单模块.md` §7.4、§8.2：
 * <ul>
 *   <li>收款不得超过订单当前未收余额；</li>
 *   <li>退款不得超过当前有效退款义务剩余额度；</li>
 *   <li>支付关联是订单事实，真实现金由 finance 保存；</li>
 *   <li>反向关联保留原事实，只追加反向记录。</li>
 * </ul>
 */
public interface OrderPaymentParticipant {

    PreparedOrderPayment preparePayment(OrderPaymentRequest request);

    OrderPaymentLinkRef commitPaymentLink(CommitOrderPaymentLink command);

    OrderPaymentLinkRef reversePaymentLink(ReverseOrderPaymentLink command);

    /** 按现金事实定位支付关联，供现金冲销使用。 */
    String findPaymentLink(String financeCashFactBusinessId);
}
