package com.yumi.product.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;

/**
 * 方案快照解析结果：冻结的目标工序、售价、供料方、工序标准与预算。
 *
 * <p>订单确认时取得本快照并保存引用；生产与成本只按快照解析，不读取方案当前值。
 */
public record SchemeSnapshotView(
        ProductSnapshotRef ref,
        String schemeBusinessId,
        SchemeType schemeType,
        String name,
        WeightGram standardWeightGrams,
        SchemeView.ProcessCode targetProcess,
        SettlementMoney salePrice,
        MaterialSource glueSource,
        MaterialSource colorPasteSource,
        int makingStandardMinutes,
        CostAmount makingCommission,
        int baggingStandardMinutes,
        CostAmount baggingLaborCost,
        int seamStandardMinutes,
        CostAmount seamLaborCost,
        CostAmount otherTaskLaborCost,
        ProductSnapshotRef productSnapshotRef,
        SchemeView.SchemeBudgetView budget) {
}
