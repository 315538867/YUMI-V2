package com.yumi.orders.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.util.Objects;

/**
 * 订单收付资格请求：由 finance.cash 在自身命令事务内提交。
 *
 * @param orderBusinessId 订单
 * @param paymentType 收款或退款
 * @param amount 本次实际金额，必须大于零
 */
public record OrderPaymentRequest(
        String orderBusinessId, OrderPaymentType paymentType, SettlementMoney amount) {

    public OrderPaymentRequest {
        if (orderBusinessId == null || orderBusinessId.isBlank()) {
            throw new IllegalArgumentException("收付款必须指定订单");
        }
        Objects.requireNonNull(paymentType, "收付类型不能为空");
        Objects.requireNonNull(amount, "收付金额不能为空");
        if (amount.value().signum() <= 0) {
            throw new IllegalArgumentException("收付金额必须大于零");
        }
    }
}
