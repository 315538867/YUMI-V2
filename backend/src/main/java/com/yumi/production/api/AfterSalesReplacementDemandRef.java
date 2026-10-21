package com.yumi.production.api;

/** 售后补发需求结果。 */
public record AfterSalesReplacementDemandRef(
        String demandBusinessId,
        String productionSourceBusinessId,
        int quantity) {
}
