package com.yumi.production.scrap.internal;

/** production_quantity_returns 一行：可分配余额 = returned_quantity − allocated_quantity。 */
public record ProductionQuantityReturnRow(
        Long id,
        long scrapRecordId,
        long orderId,
        long orderItemId,
        long productId,
        String node,
        int returnedQuantity,
        int allocatedQuantity,
        long version) {

    public int availableQuantity() {
        return returnedQuantity - allocatedQuantity;
    }
}
