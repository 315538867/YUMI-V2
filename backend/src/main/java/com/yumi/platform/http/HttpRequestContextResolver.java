package com.yumi.platform.http;

import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.http.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 从 HTTP 请求解析平台请求上下文。
 *
 * <p>规则来源：`docs/一期/05-跨模块接口与事务规则.md` §9、§11。正式写命令必须携带
 * {@code Idempotency-Key}；查询不需要幂等键。请求编号由 {@link RequestIdFilter} 统一确定并
 * 回写响应头，本解析器只读取该结果。
 *
 * <p>本解析器只负责读取请求头，不判断认证与权限；身份校验由 2.2 的身份/权限服务完成。
 */
@Component
public class HttpRequestContextResolver implements com.yumi.platform.api.http.RequestContextResolver {

    /**
     * 解析命令上下文；缺少幂等键时抛出 {@link IllegalArgumentException}，由错误映射转为
     * {@code VALIDATION}，不会产生业务写入。
     */
    @Override
    public RequestContext resolveCommand(HttpServletRequest request) {
        return RequestContext.forCommand(
                request.getHeader(HttpHeaders.IDEMPOTENCY_KEY), resolveRequestId(request));
    }

    /** 解析查询上下文；不需要幂等键。 */
    @Override
    public RequestContext resolveQuery(HttpServletRequest request) {
        return RequestContext.forQuery(resolveRequestId(request));
    }

    /** 请求编号来自过滤器；过滤器未执行时（例如非 Web 调用）使用请求头或生成新值。 */
    @Override
    public String resolveRequestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (attribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        String header = request.getHeader(HttpHeaders.REQUEST_ID);
        if (header != null && !header.isBlank()) {
            return header;
        }
        return "req-" + UUID.randomUUID();
    }

    /** 请求是否声明为正式命令。 */
    public boolean isCommand(HttpServletRequest request) {
        String key = request.getHeader(HttpHeaders.IDEMPOTENCY_KEY);
        return key != null && !key.isBlank();
    }
}
