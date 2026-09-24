package com.yumi.catalog.product;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/** 商品列表行：id/productNo/name/status/starLevelId/starName/salePrice/totalCost。 */
public record ProductSummary(
        Long id,
        String productNo,
        String name,
        String status,
        Long starLevelId,
        String starName,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal salePrice,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal totalCost) {
}
