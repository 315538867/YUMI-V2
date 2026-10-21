package com.yumi.finance.costledger.api;

import java.time.LocalDate;

/** 归集生产材料成本。 */
public record AccrueProductionMaterialCostsCommand(
        String idempotencyKey, LocalDate startDate, LocalDate endDate) {

    public AccrueProductionMaterialCostsCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("材料成本归集必须携带幂等键");
        }
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("材料成本归集日期范围不合法");
        }
    }
}
