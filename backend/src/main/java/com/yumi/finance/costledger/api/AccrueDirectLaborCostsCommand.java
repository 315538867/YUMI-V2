package com.yumi.finance.costledger.api;

import java.time.LocalDate;

/** 归集已核定工资的实际人工成本。 */
public record AccrueDirectLaborCostsCommand(
        String idempotencyKey, LocalDate startDate, LocalDate endDate) {

    public AccrueDirectLaborCostsCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("人工成本归集必须携带幂等键");
        }
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("人工成本归集日期范围不合法");
        }
    }
}
