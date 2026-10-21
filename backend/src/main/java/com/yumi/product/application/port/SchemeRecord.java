package com.yumi.product.application.port;

import com.yumi.product.api.ProductStatus;
import com.yumi.product.api.SchemeType;
import java.math.BigDecimal;
import java.time.Instant;

/** 方案当前记录（模块内部类型）。 */
public record SchemeRecord(
        long id,
        String schemeBusinessId,
        String productBusinessId,
        SchemeType schemeType,
        String name,
        BigDecimal standardWeightGrams,
        String targetProcess,
        BigDecimal salePrice,
        String glueSource,
        String colorPasteSource,
        ProductStatus status,
        long sourceProductContentVersion,
        long contentVersion,
        long rowVersion,
        Instant createdAt,
        Instant updatedAt,
        SchemeCosts costs) {

    /** 方案工序字段。 */
    public record SchemeCosts(
            int makingStandardMinutes,
            BigDecimal makingCommission,
            BigDecimal makingOtherMaterialCost,
            int baggingStandardMinutes,
            BigDecimal baggingLaborCost,
            BigDecimal baggingBagUnitCost,
            String baggingBagSource,
            BigDecimal baggingDecorationUnitPrice,
            String baggingDecorationSource,
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
