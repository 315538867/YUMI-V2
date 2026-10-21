package com.yumi.platform.api.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 跨模块 HTTP 适配器使用的幂等执行入口。
 *
 * <p>实现负责在事务内登记成功结果；请求指纹由 {@link #fingerprint(ObjectMapper, Object)}
 * 对请求体做稳定序列化后计算，业务模块不直接依赖平台内部幂等服务。
 */
public interface CommandExecutionFacade {

    CommandExecution execute(CommandRequest request, Supplier<CommandOutcome> body);

    /** 便利入口：由实现按请求体计算稳定指纹。 */
    CommandExecution execute(
            String scopeKey,
            String idempotencyKey,
            Object requestBody,
            Supplier<CommandOutcome> body);

    /** 请求体稳定指纹：与平台命令服务使用同一 SHA-256 规则。 */
    static String fingerprint(ObjectMapper objectMapper, Object requestBody) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(requestBody);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new com.yumi.platform.api.http.ApiException(
                    com.yumi.platform.api.http.ApiErrorType.VALIDATION,
                    "INVALID_REQUEST_BODY", "请求体无法序列化为稳定指纹");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }

    /** 便于非 Spring 调用方直接计算字符串指纹。 */
    static String fingerprintOf(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
