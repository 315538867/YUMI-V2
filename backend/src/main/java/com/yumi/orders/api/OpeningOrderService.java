package com.yumi.orders.api;

/**
 * 已有订单接管：只接管上线时仍未完成的订单，只写当前数量基线，不补造历史事实。
 */
public interface OpeningOrderService {

    OrderRef intake(IntakeLegacyOrderCommand command);
}
