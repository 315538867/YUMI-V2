package com.yumi.production.plan.internal;

import java.time.LocalDate;

/** 生产计划行（阶段五）：表列快照。 */
public record ProductionPlanRow(
        long id,
        String planNo,
        String planType,
        long orderId,
        long orderItemId,
        String node,
        LocalDate planDate,
        long employeeId,
        String employeeName,
        int quantity,
        String status,
        String sourceType,
        long sourceId,
        long sourceLineId,
        String note,
        long version) {
}
