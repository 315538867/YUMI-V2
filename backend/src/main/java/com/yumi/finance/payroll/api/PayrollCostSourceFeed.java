package com.yumi.finance.payroll.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 工资实际人工成本来源：供 costledger 下游归集。 */
public interface PayrollCostSourceFeed {

    List<DirectLaborCostItem> listDirectLaborCosts(LocalDate start, LocalDate end);

    record DirectLaborCostItem(
            String sourceBusinessId, String resultBusinessId, String employeeBusinessId,
            BigDecimal amount, LocalDate businessDate, String operationType, Instant occurredAt) {
    }
}
