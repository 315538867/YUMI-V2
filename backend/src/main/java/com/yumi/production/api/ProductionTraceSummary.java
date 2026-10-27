package com.yumi.production.api;

/**
 * 追溯列表摘要：只含订单项、商品与数量概览，不加载任务和事实链路。
 * 展开订单后再按订单项读取完整追溯，避免首屏 N+1。
 */
public record ProductionTraceSummary(
        String orderBusinessId,
        String orderNo,
        String orderItemBusinessId,
        String productNames,
        int demandCount,
        int requiredQuantity,
        int fulfilledQuantity) {
}
