package com.yumi.product.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import java.util.Optional;

/**
 * 方案当前定义视图。
 *
 * <p>内置标准方案与自定义方案使用同一结构；自定义方案中人工、工时与额外单件费用由商品同步，
 * 只读。目标工序只能是 {@code MAKING / BAGGING / SEAM_CUTTING}。
 *
 * @param latestBudget 当前内容版本对应的预算结果；方案保存时同事务写入
 */
public record SchemeView(
        String schemeBusinessId,
        String productBusinessId,
        SchemeType schemeType,
        String name,
        WeightGram standardWeightGrams,
        ProcessCode targetProcess,
        SettlementMoney salePrice,
        MaterialSource glueSource,
        MaterialSource colorPasteSource,
        ProductStatus status,
        long sourceProductContentVersion,
        long contentVersion,
        long rowVersion,
        MakingView making,
        BaggingView bagging,
        SeamCuttingView seamCutting,
        ProductView.ExtraUnitCostView extraCosts,
        Optional<SchemeBudgetView> latestBudget) {

    /** 目标工序。 */
    public enum ProcessCode {
        MAKING,
        BAGGING,
        SEAM_CUTTING
    }

    /** 方案内制作工序：工时与提成继承商品，只读。 */
    public record MakingView(int standardMinutes, CostAmount makingCommission, CostAmount otherMaterialCost) {
    }

    /** 方案内捏毛装袋工序：袋子与装饰品可换来源。 */
    public record BaggingView(
            int standardMinutes,
            CostAmount laborCost,
            CostAmount bagUnitCost,
            MaterialSource bagSource,
            CostAmount decorationUnitPrice,
            MaterialSource decorationSource,
            CostAmount otherCost) {
    }

    /** 方案内缝边剪袋工序：无来源选项。 */
    public record SeamCuttingView(
            int standardMinutes, CostAmount laborCost, CostAmount combinedMaterialCost, CostAmount otherCost) {
    }

    /** 预算结果视图：金额保持 4 位成本精度，利润可为负，利润率可为空。 */
    public record SchemeBudgetView(
            CostAmount fullResourceUnitCost,
            CostAmount studioBorneUnitCost,
            CostAmount customerSuppliedUnitValue,
            com.yumi.platform.api.value.SignedCostAmount expectedUnitProfit,
            Optional<java.math.BigDecimal> expectedProfitRate,
            String formulaRuleVersion,
            String roundingVersion,
            String inputFingerprint,
            java.util.List<BudgetLineView> lines) {
    }

    /** 预算明细行。 */
    public record BudgetLineView(
            String componentCode,
            String processCode,
            String costNature,
            String bearerType,
            java.math.BigDecimal quantity,
            CostAmount unitPrice,
            CostAmount fullResourceCost,
            CostAmount studioBorneCost) {
    }
}
