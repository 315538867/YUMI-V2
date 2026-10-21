package com.yumi.production.api;

import java.time.Instant;

/**
 * 公开数量投影的一行：同时给出需求义务与物理制品两套数量账，并标记缺口与延迟。
 *
 * <p>{@code gapQuantity > 0} 表示存在履约缺口；{@code pendingVerificationQuantity > 0} 表示仍有
 * 未核验计划，不能据此虚构完成数量；{@code delayedVerificationCount > 0} 表示存在延迟核验。
 */
public record ProductionProjectionRow(
        String demandBusinessId,
        String productionSourceBusinessId,
        String orderItemBusinessId,
        String productBusinessId,
        DemandType demandType,
        int requiredQuantity,
        int pendingWorkQuantity,
        int inProcessQuantity,
        int targetReadyQuantity,
        int fulfilledQuantity,
        int fulfillmentGapQuantity,
        int pendingVerificationQuantity,
        int delayedVerificationCount,
        int cumulativeMakingCreatedQuantity,
        int inSystemQuantity,
        int consumedQuantity,
        int destroyedQuantity,
        int offlineInventoryQuantity,
        Instant updatedAt) {

    /** 两套数量账都守恒才视为可信投影。 */
    public boolean balanced() {
        return requiredQuantity
                == pendingWorkQuantity + inProcessQuantity + targetReadyQuantity
                        + fulfilledQuantity + fulfillmentGapQuantity
                && cumulativeMakingCreatedQuantity
                        == inSystemQuantity + consumedQuantity + destroyedQuantity
                                + offlineInventoryQuantity;
    }
}
