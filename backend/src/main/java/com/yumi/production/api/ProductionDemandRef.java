package com.yumi.production.api;

/** 生产需求引用。 */
public record ProductionDemandRef(
        String demandBusinessId,
        DemandType demandType,
        String productionSourceBusinessId,
        String physicalRootSourceBusinessId,
        TargetProcess targetProcess,
        int requiredQuantity,
        DemandStatus status,
        long rowVersion) {
}
