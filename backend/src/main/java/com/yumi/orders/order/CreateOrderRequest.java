package com.yumi.orders.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 新建订单草稿入参：至少一条明细。
 * 收货信息未传时取客户的默认收货信息；交期可空但不得早于下单日期；
 * 优惠作用于整单应收（0 ≤ 优惠 ≤ 商品金额 + 缝边收费）。
 */
public record CreateOrderRequest(
        Long customerId,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String recipientName,
        String recipientPhone,
        String region,
        String address,
        String note,
        BigDecimal discountAmount,
        List<OrderItemRequest> items) {
}
