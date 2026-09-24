package com.yumi.catalog.customer;

/**
 * 任务 2.6 客户详情只读汇总骨架：全部为字符串（金额 scale 4，JSON 字符串下发）。
 * 首期订单/收付款事实尚不存在，一律返回 0；阶段 3（订单）/阶段 7（收付款）接线后
 * 由事实计算替换，不落任何可编辑客户字段、不建余额列。
 */
public record CustomerSummary(
        String orderCount,
        String totalOrdered,
        String totalReceived,
        String totalRefunded) {

    public static CustomerSummary zero() {
        return new CustomerSummary("0", "0.0000", "0.0000", "0.0000");
    }
}
