package com.yumi.orders.api;

import com.yumi.platform.api.value.SettlementMoney;

/**
 * 订单收付资格预检结果；只在当前事务内有效，提交时重新校验。
 */
public record PreparedOrderPayment(
        String orderBusinessId, OrderPaymentType paymentType, SettlementMoney amount) {
}
