package com.yumi.inventory.movement.internal;

import java.time.LocalDate;

/** inventory_movements 表一行：一次库存业务操作的业务头，不可变。 */
public record InventoryMovementRow(
        Long id,
        String movementNo,
        String movementType,
        LocalDate businessDate,
        String sourceType,
        long sourceId,
        long sourceLineId,
        Long reversesMovementId,
        String reason,
        String operatorUsername,
        String note) {
}
