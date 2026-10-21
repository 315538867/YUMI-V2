package com.yumi.orders.api;

import java.time.Instant;

/** 订单完成结果。 */
public record OrderCompletionRef(
        String orderBusinessId, Instant completedAt, long contentVersion) {
}
