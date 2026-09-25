package com.yumi.inventory.movement.internal;

import java.time.LocalDate;

/** inventory_movement_lines 表一行：批次、方向、数量与变动前后数量。 */
public record InventoryMovementLineRow(
        Long id,
        long movementId,
        long batchId,
        String direction,
        int quantity,
        int quantityBefore,
        int quantityAfter,
        long productId,
        String node,
        String seamState,
        Long orderItemId,
        String note) {
}
