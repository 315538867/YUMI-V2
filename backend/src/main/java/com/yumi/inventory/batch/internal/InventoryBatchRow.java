package com.yumi.inventory.batch.internal;

import java.time.LocalDate;

/** inventory_batches 表一行：商品 + 已完成工序 + 缝边状态 + 当前数量缓存。 */
public record InventoryBatchRow(
        Long id,
        String batchNo,
        long productId,
        String productNo,
        String productName,
        String sourceType,
        long sourceId,
        long sourceLineId,
        String node,
        String seamState,
        int quantity,
        LocalDate inventoryDate,
        String note,
        long version) {
}
