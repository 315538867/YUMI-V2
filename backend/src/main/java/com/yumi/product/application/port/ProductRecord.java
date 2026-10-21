package com.yumi.product.application.port;

import com.yumi.product.api.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** 商品当前聚合记录（模块内部类型）。 */
public record ProductRecord(
        long id,
        String productBusinessId,
        String productNo,
        String name,
        String description,
        BigDecimal standardWeightGrams,
        String makingStarCode,
        String makingStarName,
        int makingStandardMinutes,
        BigDecimal standardSalePrice,
        BigDecimal glueLossRate,
        BigDecimal glueUnitPrice,
        BigDecimal colorPasteUnitPrice,
        BigDecimal makingCommission,
        int mouldCount,
        int mouldDailyBatches,
        int dailyCapacity,
        ProductStatus status,
        long contentVersion,
        long rowVersion,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        ProcessCosts processCosts) {

    /** 三道工序与额外单件费用的当前值。 */
    public record ProcessCosts(
            BigDecimal makingOtherMaterialCost,
            int baggingStandardMinutes,
            BigDecimal baggingLaborCost,
            BigDecimal baggingBagUnitCost,
            BigDecimal baggingDecorationUnitPrice,
            BigDecimal baggingOtherCost,
            int seamStandardMinutes,
            BigDecimal seamLaborCost,
            BigDecimal seamCombinedMaterialCost,
            BigDecimal seamOtherCost,
            BigDecimal extraOtherTaskLaborCost,
            BigDecimal extraTransportPackagingCost,
            BigDecimal extraDailyMiscCost,
            BigDecimal extraRentUtilitiesCost,
            BigDecimal extraMouldAmortizationCost,
            BigDecimal extraOtherCost) {
    }
}
