package com.yumi.orders.api;

/** 订单状态：创建成功即为 CONFIRMED，不保存半成品。 */
public enum OrderStatus {
    CONFIRMED,
    IN_FULFILLMENT,
    COMPLETED,
    CANCELLED
}
