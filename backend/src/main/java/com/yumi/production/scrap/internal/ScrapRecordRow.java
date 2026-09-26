package com.yumi.production.scrap.internal;

import java.time.LocalDateTime;

/** scrap_records 一行：不可变报废事实，不产生替代类型、不增加订单需求。 */
public record ScrapRecordRow(
        Long id,
        long verificationId,
        long taskItemId,
        long orderId,
        long orderItemId,
        long productId,
        String node,
        int scrapQuantity,
        String reason,
        String operatorUsername,
        LocalDateTime recordedAt) {
}
