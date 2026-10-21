package com.yumi.orders.api;

/** 发货结果。 */
public record ShipmentRef(
        String shipmentBusinessId,
        String shipmentNo,
        String orderBusinessId,
        int shippedQuantity,
        int totalShippedQuantity,
        boolean deliveryFulfilled) {
}
