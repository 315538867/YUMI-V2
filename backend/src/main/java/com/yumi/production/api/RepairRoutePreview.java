package com.yumi.production.api;

/** 转路线预检结果。 */
public record RepairRoutePreview(
        String repairDemandBusinessId,
        String productionSourceBusinessId,
        int currentGapQuantity,
        int requestedQuantity,
        boolean allowed,
        String blockingReason) {
}
