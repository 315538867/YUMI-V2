package com.yumi.production.aftersales;

import java.time.LocalDate;

/**
 * 售后生产来源与售后生产任务（阶段八 8.4/8.5）的请求与视图。
 *
 * <p>阶段五只保留售后生产的**来源边界**：售后生产只能使用售后专用来源
 * （`after_sales_production_sources`），不得通过普通订单需求路径排产；
 * 售后核验、售后补发与合格流向属于阶段八业务，不在本模块实现。
 */
public final class AfterSalesProductionViews {

    private AfterSalesProductionViews() {
    }

    /**
     * 从售后来源创建生产任务：`purpose` 为 `REWORK`（退回返工）或 `REPLACEMENT`（补发缺口），
     * 其余取值一律 `VALIDATION_INVALID` 且不写任何行。
     */
    public record CreateAfterSalesTaskRequest(Long afterSalesItemId, String purpose, String node,
                                              LocalDate planDate, Long employeeId, Integer quantity,
                                              String note) {
    }

    /**
     * 售后来源读模型：额度、已安排与可安排余额。
     * `availableQuantity = totalQuantity − arrangedQuantity`。
     */
    public record AfterSalesSourceView(long id, long afterSalesItemId, String purpose, String node,
                                       int totalQuantity, int arrangedQuantity, int availableQuantity,
                                       String reason) {
    }
}
