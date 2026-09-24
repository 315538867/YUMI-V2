package com.yumi.orders.change.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** order_change_orders 表一行：变更草稿的表头变更与确认信息（未变更的 new_* 列为 NULL＝沿用原值）。 */
public record OrderChangeRow(
        Long id,
        String changeNo,
        long orderId,
        String status,
        String reason,
        LocalDate newExpectedDeliveryDate,
        String newRecipientName,
        String newRecipientPhone,
        String newRegion,
        String newAddress,
        String newNote,
        BigDecimal newDiscountAmount,
        long version) {
}
