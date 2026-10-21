package com.yumi.production.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 工资参考使用的生产材料来源（按业务日期读取当前有效项）。 */
public interface ProductionPayrollMaterialSourceFeed {

    List<MaterialItem> listForPeriod(LocalDate start, LocalDate end);

    record MaterialItem(
            String materialSourceBusinessId,
            String taskBusinessId,
            String demandBusinessId,
            String orderBusinessId,
            String componentCode,
            String materialSource,
            int quantity,
            BigDecimal unitCost,
            boolean basisComplete,
            LocalDate businessDate,
            Instant occurredAt,
            List<BasisCostLine> basisCostLines) {
    }

    record BasisCostLine(
            String componentCode, String processCode, String nature, String source,
            BigDecimal unitCost) {
    }
}
