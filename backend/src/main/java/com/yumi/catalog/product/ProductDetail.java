package com.yumi.catalog.product;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/**
 * 商品全字段响应：存量快照（星级/包装/胶水色浆单价/缝边默认值）+ 读时派生 estimatedProfit/estimatedMarginRate。
 * 包装提成为商品自身字段；缝边只保存「默认缝边剪袋类型 + 缝边价格」默认值，不含缝边数量与缝边成本，
 * {@code seamBudget} 单列给出缝边剪袋变体预算（未选默认缝边剪袋类型时为 null）。
 * 金额与比例统一以字符串输出（scale4/scale6）。
 */
public record ProductDetail(
        Long id,
        String productNo,
        String name,
        String note,
        String status,
        Long imageFileId,
        Long starLevelId,
        String starName,
        Integer starStdMinutes,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal salePrice,
        Integer weightG,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal lossRatePercent,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal glueUnitPrice,
        Integer glueGrams,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal glueCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal colorpasteUnitPrice,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal colorpasteCost,
        Integer qty8h,
        Integer qty6h,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal productLaborFee,
        Long packagingTierId,
        String packagingTierName,
        Integer packagingStdMinutes,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal packagingCommission,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal packagingLaborFee,
        Long seamTypeId,
        String seamTypeName,
        Integer seamStdMinutes,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamUnitCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal boxLaborFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal transportPackingFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal dailySundriesFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal rentUtilitiesFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal moldAmortFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal materialCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal laborCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal otherCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal referencePrice,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal estimatedProfit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal estimatedMarginRate,
        SeamBudgetView seamBudget,
        Integer moldQuantity,
        Integer dailyBatchLimit,
        Integer dailyMaxCapacity,
        Long version) {
}
