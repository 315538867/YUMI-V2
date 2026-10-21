package com.yumi.production.api;

/** 售后修复需求结果。 */
public record AfterSalesRepairDemandRef(
        String demandBusinessId,
        String productionSourceBusinessId,
        String returnedSourceBusinessId,
        int quantity,
        int availablePoolQuantity) {
}
