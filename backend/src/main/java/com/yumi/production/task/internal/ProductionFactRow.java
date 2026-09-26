package com.yumi.production.task.internal;

import java.time.LocalDateTime;

/**
 * 生产事实时间线的一行（阶段五 5.18）：把任务相关的不变事实统一成同一形状，
 * 由 SQL `UNION ALL` 聚合后按 `factTime ASC, factType ASC, factId ASC` 稳定排序。
 */
public record ProductionFactRow(
        LocalDateTime factTime,
        String factType,
        long factId,
        String node,
        Integer quantity,
        Long orderItemId,
        Long referenceId,
        String operator,
        String reason,
        String note) {
}
