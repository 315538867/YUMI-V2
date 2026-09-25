package com.yumi.inventory;

/**
 * 售后库存领用入参（任务 8.6）：从成品批次（可发货）扣库存，只增加售后可补发。
 * 售后补发不改变原订单履约，因此不走订单领用（没有订单明细与接入工序）。
 */
public record AfterSalesAllocationRequest(
        Long afterSalesItemId,
        Long batchId,
        Integer quantity,
        String reason) {
}
