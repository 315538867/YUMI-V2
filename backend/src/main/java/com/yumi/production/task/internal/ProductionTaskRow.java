package com.yumi.production.task.internal;

import java.time.LocalDate;

/** production_tasks 一行：任务头只保存共同组织信息，不承载任何数量流转字段。 */
public record ProductionTaskRow(
        Long id,
        String taskNo,
        LocalDate taskDate,
        long employeeId,
        String employeeNameSnapshot,
        long workTypeId,
        String workTypeNameSnapshot,
        String taskType,
        String note,
        long version) {
}
