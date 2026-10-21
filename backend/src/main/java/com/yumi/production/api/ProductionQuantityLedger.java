package com.yumi.production.api;

/**
 * 两套数量账的快照（基线 11 §5.2.1、§7.7）。
 *
 * <p>需求义务：{@code required = pending + inProcess + targetReady + fulfilled + gap}；
 * 物理制品：{@code cumulative = inSystem + consumed + destroyed + offline}。
 * 这两个等式是读模型的自检不变量，任何一份数量都不得被统计两次。
 */
public record ProductionQuantityLedger(
        String demandBusinessId,
        String productionSourceBusinessId,
        DemandType demandType,
        int requiredQuantity,
        int pendingWorkQuantity,
        int inProcessQuantity,
        int targetReadyQuantity,
        int fulfilledQuantity,
        int fulfillmentGapQuantity,
        PhysicalLedger physical) {

    /** 需求义务是否守恒。 */
    public boolean demandBalanced() {
        return requiredQuantity
                == pendingWorkQuantity + inProcessQuantity + targetReadyQuantity
                        + fulfilledQuantity + fulfillmentGapQuantity;
    }

    /** 物理制品是否守恒。 */
    public boolean physicalBalanced() {
        return physical == null || physical.cumulativeMakingCreatedQuantity()
                == physical.inSystemQuantity() + physical.consumedQuantity()
                        + physical.destroyedQuantity() + physical.offlineInventoryQuantity();
    }

    /** 物理制品投影。 */
    public record PhysicalLedger(
            String physicalRootSourceBusinessId,
            int cumulativeMakingCreatedQuantity,
            int inSystemQuantity,
            int consumedQuantity,
            int destroyedQuantity,
            int offlineInventoryQuantity) {
    }
}
