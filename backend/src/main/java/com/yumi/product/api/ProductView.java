package com.yumi.product.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import java.math.BigDecimal;

/**
 * 商品当前定义视图。
 *
 * <p>注意：商品<b>没有</b>目标工序字段；目标工序只存在于方案。标准售价保留在商品上，
 * 内置标准方案引用该售价。
 *
 * @param dailyCapacity 单日最大产能，由计算中心按模具数量 × 每日批次得出后保存
 */
public record ProductView(
        String productBusinessId,
        String productNo,
        String name,
        String description,
        WeightGram standardWeightGrams,
        String makingStarCode,
        String makingStarName,
        int makingStandardMinutes,
        SettlementMoney standardSalePrice,
        LossRate glueLossRate,
        CostAmount glueUnitPrice,
        CostAmount colorPasteUnitPrice,
        CostAmount makingCommission,
        int mouldCount,
        int mouldDailyBatches,
        int dailyCapacity,
        ProductStatus status,
        long contentVersion,
        long rowVersion,
        MakingProcessView making,
        BaggingProcessView bagging,
        SeamCuttingProcessView seamCutting,
        ExtraUnitCostView extraCosts) {

    /** 制作工序：人工来自商品提成，只有其他材料费用可被自定义方案覆盖。 */
    public record MakingProcessView(CostAmount otherMaterialCost) {
    }

    /** 捏毛装袋工序。 */
    public record BaggingProcessView(
            int standardMinutes,
            CostAmount laborCost,
            CostAmount bagUnitCost,
            MaterialSource bagSource,
            CostAmount decorationUnitPrice,
            MaterialSource decorationSource,
            CostAmount otherCost) {
    }

    /** 缝边剪袋工序：该工序没有客户供料选项。 */
    public record SeamCuttingProcessView(
            int standardMinutes, CostAmount laborCost, CostAmount combinedMaterialCost, CostAmount otherCost) {
    }

    /** 额外单件费用：已是每件金额，不再分摊。 */
    public record ExtraUnitCostView(
            CostAmount otherTaskLaborCost,
            CostAmount transportPackagingCost,
            CostAmount dailyMiscCost,
            CostAmount rentUtilitiesCost,
            CostAmount mouldUnitAmortizationCost,
            CostAmount otherCost) {
    }

    /** 便捷构造：统一币种为 CNY。 */
    public static CostAmount cny(String value) {
        return CostAmount.of(new BigDecimal(value), CurrencyCode.CNY);
    }
}
