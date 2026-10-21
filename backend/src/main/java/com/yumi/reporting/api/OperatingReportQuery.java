package com.yumi.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 月度经营汇总：应收、生产、成本、工资与真实现金分列。
 *
 * <p>现金净额不叫利润，成本合计也不是现金支出；工资参考、应付、成本与实际付款不合并计算。
 */
public interface OperatingReportQuery {

    MonthlyOperatingSummary getMonthlySummary(LocalDate month);

    record MonthlyOperatingSummary(
            LocalDate monthStart,
            int orderCount,
            BigDecimal orderReceivable,
            int productionRequiredQuantity,
            int productionFulfilledQuantity,
            int productionGapQuantity,
            int productionInSystemQuantity,
            int productionConsumedQuantity,
            int destroyedQuantity,
            int offlineInventoryQuantity,
            BigDecimal costTotal,
            boolean costIncomplete,
            BigDecimal payrollPayable,
            BigDecimal payrollPaid,
            BigDecimal payrollCashReturn,
            BigDecimal operatingIncome,
            BigDecimal operatingExpense,
            BigDecimal orderReceipts,
            BigDecimal orderRefunds,
            BigDecimal orderNetReceipts,
            BigDecimal afterSalesPayments,
            BigDecimal cashInflow,
            BigDecimal cashOutflow,
            BigDecimal cashNet,
            boolean lateSources) {
    }
}
