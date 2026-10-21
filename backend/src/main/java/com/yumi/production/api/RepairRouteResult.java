package com.yumi.production.api;

/** 转路线结果。 */
public record RepairRouteResult(
        String repairDemandBusinessId,
        String productionSourceBusinessId,
        int quantity,
        int gapBefore,
        int gapAfter,
        int requiredAfter) {
}
