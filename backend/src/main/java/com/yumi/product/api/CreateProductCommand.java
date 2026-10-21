package com.yumi.product.api;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;

/**
 * 创建商品命令：一次提交完整商品定义。
 *
 * <p>命令<b>不含</b>目标工序字段：商品不接收目标工序，内置标准方案固定为 {@code SEAM_CUTTING}。
 */
public record CreateProductCommand(
        String name,
        String description,
        WeightGram standardWeightGrams,
        String makingStarCode,
        int makingStandardMinutes,
        SettlementMoney standardSalePrice,
        LossRate glueLossRate,
        CostAmount glueUnitPrice,
        CostAmount colorPasteUnitPrice,
        CostAmount makingCommission,
        int mouldCount,
        int mouldDailyBatches,
        CostAmount makingOtherMaterialCost,
        int baggingStandardMinutes,
        CostAmount baggingLaborCost,
        CostAmount baggingBagUnitCost,
        CostAmount baggingDecorationUnitPrice,
        CostAmount baggingOtherCost,
        int seamStandardMinutes,
        CostAmount seamLaborCost,
        CostAmount seamCombinedMaterialCost,
        CostAmount seamOtherCost,
        CostAmount extraOtherTaskLaborCost,
        CostAmount extraTransportPackagingCost,
        CostAmount extraDailyMiscCost,
        CostAmount extraRentUtilitiesCost,
        CostAmount extraMouldAmortizationCost,
        CostAmount extraOtherCost) {
}
