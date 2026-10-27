package com.yumi.production.api;

/** 可安排生产来源：按请求工序计算剩余可安排数量。 */
public record AvailableProductionSourceView(
        String productionSourceBusinessId,
        String demandBusinessId,
        DemandType demandType,
        String productBusinessId,
        String schemeBusinessId,
        TargetProcess targetProcess,
        int initialQuantity,
        int reservedQuantity,
        int availableQuantity) {
}
