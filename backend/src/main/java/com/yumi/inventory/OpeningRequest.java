package com.yumi.inventory;

import java.time.LocalDate;

/** 期初库存入参（任务 4.3）：商品、已完成工序、缝边状态、数量与盘点日期，来源记为期初。 */
public record OpeningRequest(
        Long productId,
        String node,
        String seamState,
        Integer quantity,
        LocalDate inventoryDate,
        String note) {
}
