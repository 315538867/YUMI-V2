package com.yumi.production.aftersales;

import java.time.LocalDate;

/** 售后生产来源与售后计划（任务 8.4/8.5）的请求与视图。 */
public final class AfterSalesProductionViews {

    private AfterSalesProductionViews() {
    }

    /** 从售后来源创建生产计划：`purpose` 为 `REWORK`（退回返工）或 `REPLACEMENT`（补发缺口）。 */
    public record CreateAfterSalesPlanRequest(Long afterSalesItemId, String purpose, String node,
                                              LocalDate planDate, Long employeeId, Integer quantity,
                                              String note) {
    }

    public record AfterSalesSourceView(long id, long afterSalesItemId, String purpose, String node,
                                       int totalQuantity, int arrangedQuantity, int balance, String reason,
                                       long version) {
    }
}
