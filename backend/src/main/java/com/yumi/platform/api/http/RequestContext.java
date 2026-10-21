package com.yumi.platform.api.http;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次请求上下文：请求编号、幂等键与业务时区。
 *
 * <p>正式命令必须携带幂等键；查询不需要幂等键，但仍必须有请求编号。业务日期按业务时区解释，
 * 不依赖服务器默认时区。
 *
 * @param requestId 请求编号
 * @param idempotencyKey 幂等键；查询为空
 * @param businessZone 业务时区 ID
 */
public record RequestContext(String requestId, String idempotencyKey, String businessZone) {

    /** 一期工作室业务时区。 */
    public static final String DEFAULT_BUSINESS_ZONE = "Asia/Shanghai";

    /** 幂等键最大长度，避免把任意长文本写入幂等记录。 */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    public RequestContext {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("请求编号不能为空");
        }
        Objects.requireNonNull(businessZone, "业务时区不能为空");
        try {
            ZoneId.of(businessZone);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("业务时区不合法：" + businessZone);
        }
    }

    /** 正式命令上下文：幂等键必填。 */
    public static RequestContext forCommand(String idempotencyKey, String requestId) {
        return forCommand(idempotencyKey, requestId, DEFAULT_BUSINESS_ZONE);
    }

    public static RequestContext forCommand(String idempotencyKey, String requestId, String businessZone) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式命令必须携带幂等键");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "幂等键长度不能超过 " + MAX_IDEMPOTENCY_KEY_LENGTH + " 个字符");
        }
        return new RequestContext(requestId, idempotencyKey, businessZone);
    }

    /** 查询上下文：无幂等键。 */
    public static RequestContext forQuery(String requestId) {
        return forQuery(requestId, DEFAULT_BUSINESS_ZONE);
    }

    public static RequestContext forQuery(String requestId, String businessZone) {
        return new RequestContext(requestId, null, businessZone);
    }

    public boolean isCommand() {
        return idempotencyKey != null;
    }

    public Optional<String> idempotencyKeyOptional() {
        return Optional.ofNullable(idempotencyKey);
    }

    public ZoneId zone() {
        return ZoneId.of(businessZone);
    }

    /**
     * 相同幂等键的请求必须携带相同输入指纹；此处只提供比较语义，指纹计算由幂等实现负责。
     */
    public boolean sameIdempotencyIdentity(RequestContext other) {
        Objects.requireNonNull(other, "对比上下文不能为空");
        return isCommand() && other.isCommand() && idempotencyKey.equals(other.idempotencyKey());
    }
}
