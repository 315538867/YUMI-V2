package com.yumi.orders.aftersales.api;

/** 售后交付结果。 */
public record AfterSalesDeliveryRef(
        String deliveryFactBusinessId,
        String deliveryItemBusinessId,
        String productionConsumptionFactBusinessId,
        int quantity) {
}
