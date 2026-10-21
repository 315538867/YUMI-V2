package com.yumi.production.api;

/** 履约生产来源引用。 */
public record ProductionSourceRef(
        String productionSourceBusinessId,
        String demandBusinessId,
        SourceKind sourceKind,
        String physicalRootSourceBusinessId,
        TargetProcess targetProcess,
        int initialQuantity) {
}
