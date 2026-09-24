package com.yumi.orders.change;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 订单变更草稿入参（任务 3.7）：表头字段未传时沿用订单当前值，
 * {@code items} 非空时整体替换变更草稿的明细（草稿可自由增删行），
 * {@code reason} 记入变更单供追溯。
 */
public record CreateChangeOrderRequest(
        String reason,
        LocalDate expectedDeliveryDate,
        String recipientName,
        String recipientPhone,
        String region,
        String address,
        String note,
        BigDecimal discountAmount,
        List<OrderChangeItemRequest> items) {
}
