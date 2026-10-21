package com.yumi.product.api;

import com.yumi.platform.api.value.WeightGram;

/**
 * 商品快照解析结果：冻结的基本字段、三道工序、额外费用与单日产能。
 *
 * <p>生产与成本只从本快照取工时与人工标准，不回读商品当前值。
 */
public record ProductSnapshotView(
        ProductSnapshotRef ref,
        String productNo,
        String name,
        WeightGram standardWeightGrams,
        int makingStandardMinutes,
        int baggingStandardMinutes,
        int seamStandardMinutes,
        com.yumi.platform.api.value.CostAmount makingCommission,
        com.yumi.platform.api.value.CostAmount baggingLaborCost,
        com.yumi.platform.api.value.CostAmount seamLaborCost,
        com.yumi.platform.api.value.CostAmount otherTaskLaborCost,
        int dailyCapacity) {
}
