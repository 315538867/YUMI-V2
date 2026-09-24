package com.yumi.catalog.product;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/**
 * 商品只读试算响应（任务 2.16）：集中计算模块的完整预估结果，商品自身成本按不缝边剪袋口径；
 * {@code seamBudget} 单列给出缝边剪袋变体预算（未选默认缝边剪袋类型时为 null）。
 * 金额与比例统一以字符串输出（scale4/scale6）；{@code estimatedMarginRatePercent} 为可直接展示的百分比文本。
 * 只读试算不产生任何业务写入，也不回传商品编号或快照。
 */
public record ProductPreview(
        Integer glueGrams,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal glueCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal colorpasteCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal materialCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal productLaborFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal packagingLaborFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal boxLaborFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal laborCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal otherCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal referencePrice,
        Integer qty8h,
        Integer qty6h,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal salePrice,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal estimatedProfit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal estimatedMarginRate,
        String estimatedMarginRatePercent,
        SeamBudgetView seamBudget) {
}
