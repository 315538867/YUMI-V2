package com.yumi.production.api;

/** 修复需求当前分支数量。 */
public record RepairDemandState(
        String demandBusinessId,
        String productionSourceBusinessId,
        int requiredQuantity,
        int pendingQuantity,
        int inProcessQuantity,
        int gapQuantity,
        int fulfilledQuantity) {
}
