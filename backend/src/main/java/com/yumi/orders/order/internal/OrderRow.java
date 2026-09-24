package com.yumi.orders.order.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** orders 表一行：当前有效订单数据与金额汇总（金额 scale4）。 */
public record OrderRow(
        Long id,
        String orderNo,
        long customerId,
        String customerName,
        String status,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String recipientName,
        String recipientPhone,
        String region,
        String address,
        String note,
        BigDecimal goodsAmount,
        BigDecimal seamAmount,
        BigDecimal discountAmount,
        BigDecimal receivableAmount,
        BigDecimal goodsCostAmount,
        BigDecimal seamCostAmount,
        BigDecimal costAmount,
        BigDecimal profitAmount,
        long version) {
}
