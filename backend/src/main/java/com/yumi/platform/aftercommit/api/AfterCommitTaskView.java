package com.yumi.platform.aftercommit.api;

import java.time.Instant;
import java.util.Map;

/** 提交后任务视图：包含失败原因与重试状态，供管理页面定位。 */
public record AfterCommitTaskView(
        String taskBusinessId,
        String taskType,
        String deduplicationKey,
        AfterCommitTaskStatus status,
        int attempts,
        int maxAttempts,
        Instant nextRunAt,
        Instant leaseUntil,
        String leaseOwner,
        String lastError,
        Map<String, String> payload,
        Instant createdAt,
        Instant updatedAt) {
}
