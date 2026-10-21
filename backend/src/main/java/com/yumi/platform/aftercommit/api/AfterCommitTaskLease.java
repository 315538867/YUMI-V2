package com.yumi.platform.aftercommit.api;

import java.time.Instant;

/**
 * 已领取的任务租约。
 *
 * @param leaseOwner 执行者标识；只有持有者可以提交结果
 */
public record AfterCommitTaskLease(
        String taskBusinessId, String taskType, String leaseOwner, Instant leaseUntil, int attempt) {
}
