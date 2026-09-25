package com.yumi.inventory.allocation.internal;

import java.time.LocalDateTime;

/** inventory_allocations 表一行：一个订单一次领用的业务头。 */
public record InventoryAllocationRow(
        Long id,
        long orderId,
        String status,
        String reason,
        LocalDateTime cancelledAt,
        String cancelledBy,
        String cancelReason,
        Long reversesAllocationId,
        long version) {
}
