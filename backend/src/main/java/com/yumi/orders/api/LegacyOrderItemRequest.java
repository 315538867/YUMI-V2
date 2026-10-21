package com.yumi.orders.api;

import com.yumi.production.api.ProductionOpeningLine;
import java.util.List;

/**
 * 已有订单接管项：当前仍需管理的数量，以及该数量在接管时的生产状态。
 *
 * @param agreement 商品、方案、目标工序、数量、价格与收货约定
 * @param openingLines 生产状态明细；合计必须等于约定数量
 */
public record LegacyOrderItemRequest(
        OrderItemRequest agreement, List<ProductionOpeningLine> openingLines) {

    public LegacyOrderItemRequest {
        if (agreement == null) {
            throw new IllegalArgumentException("接管订单项必须给出完整约定");
        }
        openingLines = List.copyOf(openingLines == null ? List.of() : openingLines);
        if (openingLines.isEmpty()) {
            throw new IllegalArgumentException("接管订单项必须给出生产状态明细");
        }
        int total = openingLines.stream().mapToInt(ProductionOpeningLine::quantity).sum();
        if (total != agreement.quantity()) {
            throw new IllegalArgumentException(
                    "生产状态合计必须等于接管数量：合计 " + total + "，接管 " + agreement.quantity());
        }
    }
}
