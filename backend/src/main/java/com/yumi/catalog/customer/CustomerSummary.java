package com.yumi.catalog.customer;

/**
 * 任务 2.6 客户详情只读汇总：全部为字符串（金额 scale 4，JSON 字符串下发）。
 * 由订单与收退款事实实时聚合（`CustomerOrderSummaryReference`），
 * 不落任何可编辑客户字段、不建余额列。
 */
public record CustomerSummary(
        String orderCount,
        String totalOrdered,
        String totalReceived,
        String totalRefunded) {
}
