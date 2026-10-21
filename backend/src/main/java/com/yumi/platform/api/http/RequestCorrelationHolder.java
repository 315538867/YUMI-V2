package com.yumi.platform.api.http;

/**
 * 请求关联标识（请求编号）的请求级持有者。
 *
 * <p>由请求过滤器写入、请求结束清除。它属于跨模块的线上契约：任何模块记录审计时都需要请求编号，
 * 但都不应把它塞进业务命令签名，因此放在 {@code platform.api.http} 供所有模块读取。审计等横切记录需要请求编号，
 * 但不应把它塞进每个业务命令的签名。
 */
public final class RequestCorrelationHolder {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestCorrelationHolder() {
    }

    public static void set(String requestId) {
        REQUEST_ID.set(requestId);
    }

    public static String get() {
        return REQUEST_ID.get();
    }

    /** 缺失时返回 {@code unknown-request}，保证审计记录可写入；正常请求不会缺失。 */
    public static String getOrUnknown() {
        String requestId = REQUEST_ID.get();
        return requestId == null || requestId.isBlank() ? "unknown-request" : requestId;
    }

    public static void clear() {
        REQUEST_ID.remove();
    }
}
