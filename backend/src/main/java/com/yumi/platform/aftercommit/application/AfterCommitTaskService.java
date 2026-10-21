package com.yumi.platform.aftercommit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.aftercommit.api.AfterCommitTaskLease;
import com.yumi.platform.aftercommit.api.AfterCommitTaskStatus;
import com.yumi.platform.aftercommit.api.AfterCommitTaskView;
import com.yumi.platform.aftercommit.application.AfterCommitTaskRepository.TaskRecord;
import com.yumi.platform.aftercommit.api.AfterCommitTaskRef;
import com.yumi.platform.aftercommit.api.EnqueueAfterCommitTaskCommand;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.StableBusinessId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提交后任务服务：登记、领取、重试与人工恢复。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9、§12：
 * <ul>
 *   <li>任务记录随业务事务提交，业务回滚时不产生可执行任务；</li>
 *   <li>多个执行者同时领取同一任务时，只有一个能获得有效租约；</li>
 *   <li>失败按退避重试，超过上限进入 {@code DEAD}，原业务仍然有效；</li>
 *   <li>管理员重试/放弃沿用原任务身份与去重键，不重复产生业务事实。</li>
 * </ul>
 */
@Transactional
public class AfterCommitTaskService implements AfterCommitTaskServiceContract {

    /** 默认最大尝试次数。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    /** 默认租约时长；执行时间超过租约后由其他 worker 重新领取。 */
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(2);

    /** 退避基数：第 n 次失败等待 {@code BASE_BACKOFF × 2^(n-1)}，上限 30 分钟。 */
    public static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final AfterCommitTaskRepository tasks;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    private final Duration leaseDuration;

    private final int maxAttempts;

    public AfterCommitTaskService(
            AfterCommitTaskRepository tasks, ObjectMapper objectMapper, Clock clock) {
        this(tasks, objectMapper, clock, DEFAULT_LEASE_DURATION, DEFAULT_MAX_ATTEMPTS);
    }

    public AfterCommitTaskService(
            AfterCommitTaskRepository tasks,
            ObjectMapper objectMapper,
            Clock clock,
            Duration leaseDuration,
            int maxAttempts) {
        this.tasks = Objects.requireNonNull(tasks, "任务仓储不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "序列化器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "租约时长不能为空");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("最大尝试次数必须为正数");
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AfterCommitTaskRef enqueue(EnqueueAfterCommitTaskCommand command) {
        Objects.requireNonNull(command, "登记命令不能为空");
        if (command.taskType() == null || command.taskType().isBlank()) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        if (command.deduplicationKey() == null || command.deduplicationKey().isBlank()) {
            throw new IllegalArgumentException("去重键不能为空");
        }

        Optional<TaskRecord> existing = tasks.findByDeduplicationKey(command.taskType(), command.deduplicationKey());
        if (existing.isPresent()) {
            TaskRecord record = existing.get();
            return new AfterCommitTaskRef(
                    StableBusinessId.of(record.taskBusinessId()),
                    record.taskType(),
                    toPublicStatus(record.status()),
                    record.nextRunAt());
        }

        Instant now = clock.instant();
        TaskRecord record = new TaskRecord(
                0L,
                "TASK-" + UUID.randomUUID(),
                command.taskType(),
                command.payload() == null ? Map.of() : Map.copyOf(command.payload()),
                command.deduplicationKey(),
                AfterCommitTaskStatus.PENDING,
                now,
                null,
                null,
                0,
                maxAttempts,
                null,
                now,
                now);
        tasks.insert(record);
        return new AfterCommitTaskRef(
                StableBusinessId.of(record.taskBusinessId()), record.taskType(),
                AfterCommitTaskStatus.PENDING, record.nextRunAt());
    }

    /**
     * 领取一个可执行任务；没有可领取任务时返回空。
     *
     * <p>使用条件更新实现租约：两个 worker 同时领取同一任务时只有一个成功。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Optional<AfterCommitTaskLease> claimNext(String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("执行者标识不能为空");
        }
        Instant now = clock.instant();
        tasks.releaseExpiredLeases(now);
        for (TaskRecord candidate : tasks.findClaimable(now, 20)) {
            Instant leaseUntil = now.plus(leaseDuration);
            int attempt = candidate.attempts() + 1;
            if (tasks.claim(candidate.taskBusinessId(), leaseOwner, leaseUntil, now, attempt)) {
                return Optional.of(new AfterCommitTaskLease(
                        candidate.taskBusinessId(), candidate.taskType(), leaseOwner, leaseUntil, attempt));
            }
        }
        return Optional.empty();
    }

    /** 执行成功：只有租约持有者可以提交。 */
    public void complete(AfterCommitTaskLease lease) {
        Objects.requireNonNull(lease, "租约不能为空");
        if (!tasks.markSucceeded(lease.taskBusinessId(), lease.leaseOwner(), clock.instant())) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "TASK_LEASE_CONFLICT",
                    "任务租约已失效，结果未提交");
        }
    }

    /**
     * 执行失败：按退避安排重试，超过上限进入 {@code DEAD}；不回滚已提交业务。
     */
    public void fail(AfterCommitTaskLease lease, String errorMessage) {
        Objects.requireNonNull(lease, "租约不能为空");
        Instant now = clock.instant();
        String message = truncate(errorMessage == null || errorMessage.isBlank() ? "未提供失败原因" : errorMessage);
        if (lease.attempt() >= maxAttempts) {
            if (!tasks.markDead(lease.taskBusinessId(), lease.leaseOwner(), message, now)) {
                throw leaseConflict();
            }
            return;
        }
        Instant nextRunAt = now.plus(backoffFor(lease.attempt()));
        if (!tasks.markRetryWait(lease.taskBusinessId(), lease.leaseOwner(), nextRunAt, message, now)) {
            throw leaseConflict();
        }
    }

    /** 管理重试：仅对 DEAD 任务有效，恢复为立即可执行。 */
    public void retry(String taskBusinessId) {
        if (!tasks.reopenForRetry(taskBusinessId, clock.instant())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "TASK_NOT_DEAD", "只有已终止的任务可以人工重试");
        }
    }

    /** 管理放弃：标记终止并保留原因，不修改原业务事实。 */
    public void abandon(String taskBusinessId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REASON", "放弃任务必须填写原因");
        }
        if (!tasks.abandon(taskBusinessId, truncate(reason), clock.instant())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "TASK_NOT_ABANDONABLE", "当前任务状态不允许放弃");
        }
    }

    /** 读取任务视图。 */
    public AfterCommitTaskView view(String taskBusinessId) {
        TaskRecord record = tasks.findByTaskBusinessId(taskBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在"));
        return toView(record);
    }

    /** 按状态列出任务，供管理页面定位失败来源。 */
    public List<AfterCommitTaskView> listByStatus(AfterCommitTaskStatus status, int limit) {
        return tasks.findByStatus(status, Math.max(1, Math.min(limit, 200))).stream()
                .map(AfterCommitTaskService::toView)
                .toList();
    }

    private static ApiException leaseConflict() {
        return new ApiException(
                ApiErrorType.CONCURRENT_CONFLICT, "TASK_LEASE_CONFLICT", "任务租约已失效，结果未提交");
    }

    private static Duration backoffFor(int attempt) {
        Duration backoff = BASE_BACKOFF.multipliedBy(1L << Math.min(attempt - 1, 10));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private static String truncate(String message) {
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static AfterCommitTaskStatus toPublicStatus(AfterCommitTaskStatus status) {
        return status;
    }

    private static AfterCommitTaskView toView(TaskRecord record) {
        return new AfterCommitTaskView(
                record.taskBusinessId(),
                record.taskType(),
                record.deduplicationKey(),
                record.status(),
                record.attempts(),
                record.maxAttempts(),
                record.nextRunAt(),
                record.leaseUntil(),
                record.leaseOwner(),
                record.lastError(),
                record.payload(),
                record.createdAt(),
                record.updatedAt());
    }
}
