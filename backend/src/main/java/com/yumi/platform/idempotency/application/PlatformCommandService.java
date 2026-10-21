package com.yumi.platform.idempotency.application;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandExecution;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import com.yumi.platform.api.transaction.DeadlockRetryTemplate;
import com.yumi.platform.transaction.SpringDeadlockDetector;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 带事务边界的正式命令执行入口。
 *
 * <p>HTTP 适配层与后台调用方通过本服务把“开始事务 → 幂等判定 → 业务写入 → 提交”作为一个整体执行：
 * <ul>
 *   <li>幂等成功记录与业务写入同事务，失败时一起回滚；</li>
 *   <li>相同幂等键与相同输入返回原结果，不重复产生业务事实；</li>
 *   <li>相同幂等键与不同输入返回 {@code IDEMPOTENCY_CONFLICT}；</li>
 *   <li>死锁与锁等待超时整体重试，不局部重跑 SQL。</li>
 * </ul>
 *
 * <p>命令体内部只能调用已声明的公开命令；本服务不感知具体业务模块。
 */
public class PlatformCommandService {

    private final PersistentIdempotentCommandService idempotency;
    private final TransactionTemplate transactionTemplate;
    private final DeadlockRetryTemplate retryTemplate = new DeadlockRetryTemplate();

    public PlatformCommandService(
            PersistentIdempotentCommandService idempotency,
            PlatformTransactionManager transactionManager) {
        this.idempotency = Objects.requireNonNull(idempotency, "幂等服务不能为空");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    /**
     * 执行一条正式命令。
     *
     * @param request 幂等身份（范围、键、输入指纹）
     * @param body 命令体；必须返回结果引用
     */
    public CommandExecution execute(CommandRequest request, Supplier<CommandOutcome> body) {
        Objects.requireNonNull(request, "幂等命令请求不能为空");
        Objects.requireNonNull(body, "命令体不能为空");
        return retryTemplate.execute(
                () -> transactionTemplate.execute(status -> idempotency.execute(request, body)),
                SpringDeadlockDetector.instance());
    }

    /** 把请求体序列化为稳定指纹：字段顺序固定，忽略请求体空白差异。 */
    public static String fingerprint(
            com.fasterxml.jackson.databind.ObjectMapper objectMapper, Object requestBody) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(requestBody);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "INVALID_REQUEST_BODY", "请求体无法序列化为稳定指纹");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }

    /** 便于测试的指纹工具：对字符串内容取 SHA-256。 */
    public static String fingerprintOf(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
