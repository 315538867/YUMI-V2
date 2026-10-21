package com.yumi.production.application.port;

/**
 * 需求义务与物理制品投影（模块内部类型，均非负）。
 *
 * <p>基线 11 §5.2.1 与 §7.7：
 * {@code required = pending_work + in_process + target_ready + fulfilled + fulfillment_gap}；
 * {@code cumulative_making_created = in_system + consumed + destroyed + offline_inventory}。
 */
public record QuantityProjections(
        DemandProjection demand, PhysicalProjection physical) {

    /** 需求义务投影。 */
    public record DemandProjection(
            String demandBusinessId,
            int requiredQuantity,
            int pendingWorkQuantity,
            int inProcessQuantity,
            int targetReadyQuantity,
            int fulfilledQuantity,
            int fulfillmentGapQuantity,
            long rowVersion) {

        public DemandProjection {
            int sum = pendingWorkQuantity + inProcessQuantity + targetReadyQuantity
                    + fulfilledQuantity + fulfillmentGapQuantity;
            if (sum != requiredQuantity) {
                throw new IllegalStateException(
                        "需求义务不守恒：required=" + requiredQuantity + "，分量合计=" + sum);
            }
        }
    }

    /** 物理制品投影。 */
    public record PhysicalProjection(
            String physicalRootSourceBusinessId,
            int cumulativeMakingCreatedQuantity,
            int inSystemQuantity,
            int consumedQuantity,
            int destroyedQuantity,
            int offlineInventoryQuantity,
            long rowVersion) {

        public PhysicalProjection {
            int sum = inSystemQuantity + consumedQuantity + destroyedQuantity + offlineInventoryQuantity;
            if (sum != cumulativeMakingCreatedQuantity) {
                throw new IllegalStateException(
                        "物理制品不守恒：cumulative=" + cumulativeMakingCreatedQuantity + "，分量合计=" + sum);
            }
        }
    }
}
