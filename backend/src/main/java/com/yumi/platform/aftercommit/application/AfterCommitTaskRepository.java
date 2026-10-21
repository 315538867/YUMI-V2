package com.yumi.platform.aftercommit.application;

import com.yumi.platform.aftercommit.api.AfterCommitTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 提交后任务持久化端口。 */
public interface AfterCommitTaskRepository {

    /** 幂等登记：同类型同去重键已存在时不新增，返回既有记录。 */
    Optional<TaskRecord> findByDeduplicationKey(String taskType, String deduplicationKey);

    Optional<TaskRecord> findByTaskBusinessId(String taskBusinessId);

    void insert(TaskRecord record);

    /**
     * 条件领取：仅当任务处于 {@code PENDING} 或到期的 {@code RETRY_WAIT}，且当前无有效租约时成功。
     *
     * @return 是否领取成功；并发领取只有一个成功
     */
    boolean claim(
            String taskBusinessId,
            String leaseOwner,
            Instant leaseUntil,
            Instant now,
            int attempt);

    boolean markSucceeded(String taskBusinessId, String leaseOwner, Instant now);

    boolean markRetryWait(
            String taskBusinessId, String leaseOwner, Instant nextRunAt, String errorMessage, Instant now);

    boolean markDead(String taskBusinessId, String leaseOwner, String errorMessage, Instant now);

    /** 管理员重试：把 DEAD 任务恢复为 PENDING，清除租约并重置退避；沿用原任务身份与去重键。 */
    boolean reopenForRetry(String taskBusinessId, Instant now);

    /** 管理员放弃：把任务标记为 DEAD 并记录原因，不修改原业务。 */
    boolean abandon(String taskBusinessId, String reason, Instant now);

    List<TaskRecord> findByStatus(AfterCommitTaskStatus status, int limit);

    /** 可领取任务：PENDING 或到期的 RETRY_WAIT，且无有效租约。 */
    List<TaskRecord> findClaimable(Instant now, int limit);

    int releaseExpiredLeases(Instant now);

    /** 任务记录（平台内部类型）。 */
    record TaskRecord(
            long id,
            String taskBusinessId,
            String taskType,
            Map<String, String> payload,
            String deduplicationKey,
            AfterCommitTaskStatus status,
            Instant nextRunAt,
            Instant leaseUntil,
            String leaseOwner,
            int attempts,
            int maxAttempts,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {
    }
}
