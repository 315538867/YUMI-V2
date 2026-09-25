package com.yumi.inventory.allocation.internal;

/** inventory_allocation_lines 表一行：领用的批次、订单明细、数量、接入节点与对应流水/履约记录。 */
public record InventoryAllocationLineRow(
        Long id,
        long allocationId,
        long batchId,
        long orderItemId,
        int quantity,
        String targetNode,
        long movementLineId,
        long fulfillmentEntryId,
        Long reversesLineId) {
}
