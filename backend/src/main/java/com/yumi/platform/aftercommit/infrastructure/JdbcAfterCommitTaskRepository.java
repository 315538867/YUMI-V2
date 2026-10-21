package com.yumi.platform.aftercommit.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.aftercommit.api.AfterCommitTaskStatus;
import com.yumi.platform.aftercommit.application.AfterCommitTaskRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 提交后任务持久化（MySQL）。
 *
 * <p>领取使用条件更新（{@code WHERE status IN (...) AND (lease_until IS NULL OR lease_until < now)}），
 * 影响行数为 1 表示租约获取成功；并发 worker 只有一个能成功。
 */
@Repository
public class JdbcAfterCommitTaskRepository implements AfterCommitTaskRepository {

    private final JdbcClient jdbc;

    private final ObjectMapper objectMapper;

    public JdbcAfterCommitTaskRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TaskRecord> findByDeduplicationKey(String taskType, String deduplicationKey) {
        return jdbc.sql(SELECT + " WHERE task_type = :taskType AND deduplication_key = :deduplicationKey")
                .param("taskType", taskType)
                .param("deduplicationKey", deduplicationKey)
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<TaskRecord> findByTaskBusinessId(String taskBusinessId) {
        return jdbc.sql(SELECT + " WHERE task_business_id = :taskBusinessId")
                .param("taskBusinessId", taskBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public void insert(TaskRecord record) {
        jdbc.sql("""
                        INSERT INTO platform_after_commit_tasks (
                            task_business_id, task_type, payload_json, deduplication_key, status,
                            next_run_at, lease_until, lease_owner, attempts, max_attempts, last_error,
                            created_at, updated_at)
                        VALUES (:taskBusinessId, :taskType, :payloadJson, :deduplicationKey, :status,
                                :nextRunAt, NULL, NULL, 0, :maxAttempts, NULL, :now, :now)
                        """)
                .param("taskBusinessId", record.taskBusinessId())
                .param("taskType", record.taskType())
                .param("payloadJson", toJson(record.payload()))
                .param("deduplicationKey", record.deduplicationKey())
                .param("status", record.status().name())
                .param("nextRunAt", JdbcTimes.toUtc(record.nextRunAt()))
                .param("maxAttempts", record.maxAttempts())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public boolean claim(
            String taskBusinessId, String leaseOwner, Instant leaseUntil, Instant now, int attempt) {
        return jdbc.sql("""
                        UPDATE platform_after_commit_tasks
                        SET status = 'RUNNING', lease_owner = :leaseOwner, lease_until = :leaseUntil,
                            attempts = :attempt, updated_at = :now
                        WHERE task_business_id = :taskBusinessId
                          AND status IN ('PENDING', 'RETRY_WAIT')
                          AND next_run_at <= :now
                          AND (lease_until IS NULL OR lease_until < :now)
                        """)
                .param("leaseOwner", leaseOwner)
                .param("leaseUntil", JdbcTimes.toUtc(leaseUntil))
                .param("attempt", attempt)
                .param("now", JdbcTimes.toUtc(now))
                .param("taskBusinessId", taskBusinessId)
                .update() == 1;
    }

    @Override
    public boolean markSucceeded(String taskBusinessId, String leaseOwner, Instant now) {
        return jdbc.sql("""
                        UPDATE platform_after_commit_tasks
                        SET status = 'SUCCEEDED', lease_until = NULL, lease_owner = NULL,
                            last_error = NULL, updated_at = :now
                        WHERE task_business_id = :taskBusinessId AND status = 'RUNNING'
                          AND lease_owner = :leaseOwner
                        """)
                .param("now", JdbcTimes.toUtc(now))
                .param("taskBusinessId", taskBusinessId)
                .param("leaseOwner", leaseOwner)
                .update() == 1;
    }

    @Override
    public boolean markRetryWait(
            String taskBusinessId, String leaseOwner, Instant nextRunAt, String errorMessage, Instant now) {
        return jdbc.sql("""
                        UPDATE platform_after_commit_tasks
                        SET status = 'RETRY_WAIT', lease_until = NULL, lease_owner = NULL,
                            next_run_at = :nextRunAt, last_error = :lastError, updated_at = :now
                        WHERE task_business_id = :taskBusinessId AND status = 'RUNNING'
                          AND lease_owner = :leaseOwner
                        """)
                .param("nextRunAt", JdbcTimes.toUtc(nextRunAt))
                .param("lastError", errorMessage)
                .param("now", JdbcTimes.toUtc(now))
                .param("taskBusinessId", taskBusinessId)
                .param("leaseOwner", leaseOwner)
                .update() == 1;
    }

    @Override
    public boolean markDead(String taskBusinessId, String leaseOwner, String errorMessage, Instant now) {
        return jdbc.sql("""
                        UPDATE platform_after_commit_tasks
                        SET status = 'DEAD', lease_until = NULL, lease_owner = NULL,
                            last_error = :lastError, updated_at = :now
                        WHERE task_business_id = :taskBusinessId AND status = 'RUNNING'
                          AND lease_owner = :leaseOwner
                        """)
                .param("lastError", errorMessage)
                .param("now", JdbcTimes.toUtc(now))
                .param("taskBusinessId", taskBusinessId)
                .param("leaseOwner", leaseOwner)
                .update() == 1;
    }

    @Override
    public boolean reopenForRetry(String taskBusinessId, Instant now) {
        return jdbc.sql("""
                        UPDATE platform_after_commit_tasks
                        SET status = 'PENDING', next_run_at = :now, attempts = 0,
                            lease_until = NULL, lease_owner = NULL, updated_at = :now
                        WHERE task_business_id = :taskBusinessId AND status = 'DEAD'
                        """)
                .param("now", JdbcTimes.toUtc(now))
                .param("taskBusinessId", taskBusinessId)
                .update() == 1;
    }

    @Override
    public boolean abandon(String taskBusinessId, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE platform_after_commit_tasks
                        SET status = 'DEAD', lease_until = NULL, lease_owner = NULL,
                            last_error = :reason, updated_at = :now
                        WHERE task_business_id = :taskBusinessId
                          AND status IN ('PENDING', 'RETRY_WAIT', 'RUNNING', 'DEAD')
                        """)
                .param("reason", reason)
                .param("now", JdbcTimes.toUtc(now))
                .param("taskBusinessId", taskBusinessId)
                .update() == 1;
    }

    @Override
    public List<TaskRecord> findByStatus(AfterCommitTaskStatus status, int limit) {
        return jdbc.sql(SELECT + " WHERE status = :status ORDER BY updated_at DESC, id DESC LIMIT :limit")
                .param("status", status.name())
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    @Override
    public List<TaskRecord> findClaimable(Instant now, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE status IN ('PENDING', 'RETRY_WAIT') AND next_run_at <= :now
                           AND (lease_until IS NULL OR lease_until < :now)
                         ORDER BY next_run_at, id LIMIT :limit
                        """)
                .param("now", JdbcTimes.toUtc(now))
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    @Override
    public int releaseExpiredLeases(Instant now) {
        return jdbc.sql("""
                        UPDATE platform_after_commit_tasks
                        SET status = 'RETRY_WAIT', lease_until = NULL, lease_owner = NULL,
                            next_run_at = :now, last_error = '租约过期，任务被重新排入队列', updated_at = :now
                        WHERE status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < :now
                        """)
                .param("now", JdbcTimes.toUtc(now))
                .update();
    }

    private static final String SELECT = """
            SELECT id, task_business_id, task_type, payload_json, deduplication_key, status,
                   next_run_at, lease_until, lease_owner, attempts, max_attempts, last_error,
                   created_at, updated_at
            FROM platform_after_commit_tasks
            """;

    private TaskRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TaskRecord(
                resultSet.getLong("id"),
                resultSet.getString("task_business_id"),
                resultSet.getString("task_type"),
                fromJson(resultSet.getString("payload_json")),
                resultSet.getString("deduplication_key"),
                AfterCommitTaskStatus.valueOf(resultSet.getString("status")),
                JdbcTimes.toInstant(resultSet.getObject("next_run_at", LocalDateTime.class)),
                JdbcTimes.toInstant(resultSet.getObject("lease_until", LocalDateTime.class)),
                resultSet.getString("lease_owner"),
                resultSet.getInt("attempts"),
                resultSet.getInt("max_attempts"),
                resultSet.getString("last_error"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)));
    }

    private String toJson(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("任务载荷无法序列化", exception);
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(java.util.LinkedHashMap.class, String.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("任务载荷无法解析", exception);
        }
    }
}
