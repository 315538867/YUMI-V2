package com.yumi.orders.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.Instant;
import java.time.LocalDate;

/** 订单列表摘要。 */
public record OrderSummaryView(
        String orderBusinessId,
        String orderNo,
        OrderOrigin origin,
        OrderStatus status,
        String customerBusinessId,
        String customerName,
        String productNames,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        SettlementMoney currentReceivable,
        SettlementMoney netReceipts,
        boolean deliveryFulfilled,
        boolean paymentFulfilled,
        long contentVersion,
        Instant updatedAt) {
}
