package com.yumi.product.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 预算结果持久化端口。 */
public interface BudgetRepository {

    Optional<BudgetRecord> findByScheme(String schemeBusinessId);

    void replace(BudgetRecord record);

    /** 预算结果与明细行（模块内部类型）。 */
    record BudgetRecord(
            String budgetBusinessId,
            String schemeBusinessId,
            long productContentVersion,
            long schemeContentVersion,
            String formulaRuleVersion,
            String roundingVersion,
            String inputFingerprint,
            BigDecimal fullResourceUnitCost,
            BigDecimal studioBorneUnitCost,
            BigDecimal customerSuppliedUnitValue,
            BigDecimal expectedUnitRevenue,
            BigDecimal expectedUnitProfit,
            BigDecimal expectedProfitRate,
            List<BudgetLineRecord> lines) {
    }

    /** 单条预算明细。 */
    record BudgetLineRecord(
            int lineNo,
            String processCode,
            String componentCode,
            String costNature,
            String bearerType,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal fullResourceCost,
            BigDecimal studioBorneCost) {
    }
}
