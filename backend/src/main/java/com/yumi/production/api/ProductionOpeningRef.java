package com.yumi.production.api;

/** 期初初始化结果。 */
public record ProductionOpeningRef(
        String demandBusinessId,
        String productionSourceBusinessId,
        String openingBatchBusinessId,
        int initializedQuantity) {
}
