package com.yumi.production.api;

/** 减量结果。 */
public record DemandReductionResult(
        String demandBusinessId,
        String changeFactBusinessId,
        int requiredQuantity,
        int cancelUnproducedQuantity,
        int transferProducedQuantity) {
}
