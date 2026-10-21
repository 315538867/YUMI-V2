package com.yumi.platform.api.http;

/** 平台约定的 HTTP 请求头名称。 */
public final class HttpHeaders {

    /** 正式写命令的幂等键；相同键与相同输入返回原结果。 */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** 请求编号，用于审计与问题定位。 */
    public static final String REQUEST_ID = "X-Request-Id";

    /** 会话令牌；值为 {@code Bearer <token>}。 */
    public static final String AUTHORIZATION = "Authorization";

    private HttpHeaders() {
    }
}
