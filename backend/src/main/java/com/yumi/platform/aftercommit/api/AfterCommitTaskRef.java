package com.yumi.platform.aftercommit.api;

import com.yumi.platform.api.value.StableBusinessId;
import java.time.Instant;

/** 提交后任务引用。 */
public record AfterCommitTaskRef(
        StableBusinessId taskBusinessId, String taskType, AfterCommitTaskStatus status, Instant nextRunAt) {
}
