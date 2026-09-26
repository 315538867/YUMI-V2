package com.yumi.catalog.product.internal;

import java.math.BigDecimal;

/**
 * products 表一行的完整业务列快照（金额 scale4、比例 scale6）。
 * 缝边只保存默认值（默认缝边剪袋类型 id/名称快照 + 缝边价格），不保存缝边数量或缝边成本；
 * 总成本与参考售价按不缝边剪袋口径。
 */
public record ProductRow(
        Long id,
        String productNo,
        String name,
        String note,
        String status,
        Long imageFileId,
        long starLevelId,
        String starName,
        int starStdMinutes,
        BigDecimal salePrice,
        int weightG,
        BigDecimal lossRate,
        BigDecimal glueUnitPrice,
        int glueGrams,
        BigDecimal glueCost,
        BigDecimal colorpasteUnitPrice,
        BigDecimal colorpasteCost,
        int qty8h,
        int qty6h,
        BigDecimal productLaborFee,
        Long packagingTierId,
        String packagingTierName,
        Integer packagingStdMinutes,
        BigDecimal packagingCommission,
        BigDecimal packagingLaborFee,
        Long seamTypeId,
        String seamTypeName,
        Integer seamStdMinutes,
        BigDecimal seamUnitCost,
        BigDecimal seamFee,
        BigDecimal boxLaborFee,
        BigDecimal transportPackingFee,
        BigDecimal dailySundriesFee,
        BigDecimal rentUtilitiesFee,
        BigDecimal moldAmortFee,
        BigDecimal materialCost,
        BigDecimal laborCost,
        BigDecimal otherCost,
        BigDecimal totalCost,
        BigDecimal referencePrice,
        int moldQuantity,
        int dailyBatchLimit,
        long version) {
}
