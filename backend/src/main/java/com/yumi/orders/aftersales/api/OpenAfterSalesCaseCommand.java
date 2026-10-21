package com.yumi.orders.aftersales.api;

import java.util.List;

/** 受理售后案例命令：只允许引用一个已完成订单。 */
public record OpenAfterSalesCaseCommand(
        String idempotencyKey,
        String orderBusinessId,
        String issueSummary,
        AfterSalesRouteType routeType,
        List<AfterSalesObligationRequest> obligations) {

    public OpenAfterSalesCaseCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式受理命令必须携带幂等键");
        }
        if (orderBusinessId == null || orderBusinessId.isBlank()) {
            throw new IllegalArgumentException("售后必须指定订单");
        }
        if (issueSummary == null || issueSummary.isBlank()) {
            throw new IllegalArgumentException("售后必须填写问题说明");
        }
        if (routeType == null) {
            throw new IllegalArgumentException("售后必须明确处理路线");
        }
        obligations = List.copyOf(obligations == null ? List.of() : obligations);
        if (obligations.isEmpty()) {
            throw new IllegalArgumentException("售后受理必须至少给出一项义务");
        }
    }
}
