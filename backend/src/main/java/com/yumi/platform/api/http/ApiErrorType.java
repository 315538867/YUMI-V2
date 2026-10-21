package com.yumi.platform.api.http;

import org.springframework.http.HttpStatus;

/**
 * 稳定错误类型（基线 05 §9、§11）。
 *
 * <p>页面必须按类型分别处理，而不是解析消息文本。类型到 HTTP 状态的映射集中在此，
 * 避免各模块适配器各自决定状态码。
 */
public enum ApiErrorType {

    /** 请求格式、字段类型或数值精度不合法。 */
    VALIDATION(HttpStatus.BAD_REQUEST),

    /** 请求合法但违反业务规则。 */
    BUSINESS_REJECTION(HttpStatus.UNPROCESSABLE_ENTITY),

    /** 目标记录不存在或不可见。 */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 未认证或缺少权限。 */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** 行版本冲突、死锁重试耗尽等并发冲突。 */
    CONCURRENT_CONFLICT(HttpStatus.CONFLICT),

    /** 相同幂等键使用了不同输入。 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),

    /** 未预期的系统失败；不向客户端暴露内部细节。 */
    SYSTEM_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ApiErrorType(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus.value();
    }
}
