package com.yumi.orders.aftersales.api;

/** 返修需求结果。 */
public record AfterSalesRepairRef(
        String demandBusinessId,
        String productionSourceBusinessId,
        String returnedSourceBusinessId,
        int quantity) {
}
