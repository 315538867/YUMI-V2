package com.yumi.catalog.product;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.yumi.calculation.product.ProductPricing;

import java.math.BigDecimal;

/**
 * 缝边剪袋变体预算视图（单件口径，公式 FP-PROD-20/21）：商品自身总成本按不缝边剪袋口径保存，
 * 本视图单列展示「不缝边剪袋总成本 + 缝边种类成本单价」的缝边剪袋变体与参考售价，不写入商品快照。
 * 商品未选默认缝边剪袋类型时为 {@code null}（默认不缝边剪袋，无缝边剪袋变体）。
 */
public record SeamBudgetView(
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamUnitCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamFee,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalCost,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal referencePrice) {

    static SeamBudgetView of(ProductPricing.SeamBudget budget) {
        return new SeamBudgetView(budget.seamUnitCost(), budget.seamFee(),
                budget.totalCost(), budget.referencePrice());
    }
}
