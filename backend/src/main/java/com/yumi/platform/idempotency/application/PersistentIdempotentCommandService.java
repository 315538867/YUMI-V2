package com.yumi.platform.idempotency.application;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandExecution;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 持久幂等命令执行。
 *
 * <p>规则来源：`docs/一期/05-跨模块接口与事务规则.md` §9。
 *
 * <p>执行流程：
 * <ol>
 *   <li>先尝试插入 {@code PROCESSING} 幂等守卫记录；唯一键冲突说明该键已被使用；</li>
 *   <li>冲突时<b>不加锁</b>读取既有记录：指纹不同立即返回 {@code IDEMPOTENCY_CONFLICT}；
 *       已成功则返回原结果；仍为 {@code PROCESSING} 则返回“处理中”冲突，由客户端稍后查询；</li>
 *   <li>首次执行在同一事务内运行命令体并写入 {@code SUCCEEDED} 与结果引用；</li>
 *   <li>命令体抛出异常时整个事务回滚，守卫记录与业务写入一起消失，重试可再次执行。</li>
 * </ol>
 *
 * <p>并发请求只会有一次插入成功，其余进入冲突分支读取已提交状态；数据库唯一键是唯一裁决者。
 * 冲突分支刻意不加 {@code FOR UPDATE}：重复键插入已持有既有索引记录的共享锁，
 * 再升级为排他锁会与并发插入形成死锁，而这里只需要读取最新已提交状态。
 */
@Transactional
public class PersistentIdempotentCommandService implements IdempotentCommandServiceContract {

    private final JdbcClient jdbc;

    public PersistentIdempotentCommandService(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcClient 不能为空");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CommandExecution execute(CommandRequest request, Supplier<CommandOutcome> body) {
        Objects.requireNonNull(request, "幂等命令请求不能为空");
        Objects.requireNonNull(body, "命令体不能为空");

        if (claim(request)) {
            CommandOutcome outcome = body.get();
            if (outcome == null) {
                throw new IllegalStateException("正式命令必须返回业务结果引用");
            }
            jdbc.sql("""
                            UPDATE platform_idempotency_records
                            SET status = 'SUCCEEDED', result_type = :resultType,
                                result_business_id = :resultBusinessId, updated_at = UTC_TIMESTAMP(6)
                            WHERE scope_key = :scopeKey AND idempotency_key = :idempotencyKey
                            """)
                    .param("resultType", outcome.resultType())
                    .param("resultBusinessId", outcome.resultBusinessId())
                    .param("scopeKey", request.scopeKey())
                    .param("idempotencyKey", request.idempotencyKey())
                    .update();
            return new CommandExecution(outcome, false);
        }

        return replay(request);
    }

    private boolean claim(CommandRequest request) {
        try {
            jdbc.sql("""
                            INSERT INTO platform_idempotency_records (
                                scope_key, idempotency_key, request_fingerprint, status, created_at, updated_at)
                            VALUES (:scopeKey, :idempotencyKey, :fingerprint, 'PROCESSING',
                                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                            """)
                    .param("scopeKey", request.scopeKey())
                    .param("idempotencyKey", request.idempotencyKey())
                    .param("fingerprint", request.requestFingerprint())
                    .update();
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private CommandExecution replay(CommandRequest request) {
        return jdbc.sql("""
                        SELECT request_fingerprint, status, result_type, result_business_id
                        FROM platform_idempotency_records
                        WHERE scope_key = :scopeKey AND idempotency_key = :idempotencyKey
                        """)
                .param("scopeKey", request.scopeKey())
                .param("idempotencyKey", request.idempotencyKey())
                .query((resultSet, rowNumber) -> new StoredRecord(
                        resultSet.getString("request_fingerprint"),
                        resultSet.getString("status"),
                        resultSet.getString("result_type"),
                        resultSet.getString("result_business_id")))
                .optional()
                .map(stored -> {
                    if (!stored.fingerprint().equals(request.requestFingerprint())) {
                        throw new ApiException(
                                ApiErrorType.IDEMPOTENCY_CONFLICT,
                                "IDEMPOTENCY_CONFLICT",
                                "相同幂等键使用了不同输入，请更换幂等键或重新读取最新状态");
                    }
                    if (!"SUCCEEDED".equals(stored.status())) {
                        throw new ApiException(
                                ApiErrorType.CONCURRENT_CONFLICT,
                                "IDEMPOTENCY_IN_PROGRESS",
                                "相同命令正在处理中，请稍后查询结果");
                    }
                    return new CommandExecution(
                            new CommandOutcome(stored.resultType(), stored.resultBusinessId()), true);
                })
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT,
                        "IDEMPOTENCY_IN_PROGRESS",
                        "相同命令正在处理中，请稍后查询结果"));
    }

    private record StoredRecord(String fingerprint, String status, String resultType, String resultBusinessId) {
    }
}
