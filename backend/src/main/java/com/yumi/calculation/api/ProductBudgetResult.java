package com.yumi.calculation.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.SignedCostAmount;
import com.yumi.platform.api.value.WeightGram;
import java.util.List;
import java.util.Optional;

/**
 * 商品单件预算结果。
 *
 * <p>守恒关系（基线 07 §4.2.3）：
 * <pre>
 * Σ full_i = 全资源单件成本
 * 工作室承担单件成本 + 客户提供材料单件价值 = 全资源单件成本
 * </pre>
 *
 * @param glueUsage 含损耗的胶水标准用量（克/件）
 * @param lines 预算明细，按稳定顺序
 * @param fullCost 全资源单件成本
 * @param studioCost 工作室承担单件成本
 * @param customerSuppliedValue 客户提供材料单件价值
 * @param estimatedProfit 预计单件利润；允许为负
 * @param profitMargin 预计利润率；售价为零时为空
 * @param trace 公式追溯标识
 */
public record ProductBudgetResult(
        WeightGram glueUsage,
        List<BudgetLine> lines,
        CostAmount fullCost,
        CostAmount studioCost,
        CostAmount customerSuppliedValue,
        SignedCostAmount estimatedProfit,
        Optional<com.yumi.calculation.api.Ratio> profitMargin,
        FormulaTrace trace) {

    public ProductBudgetResult {
        lines = List.copyOf(lines);
    }
}
