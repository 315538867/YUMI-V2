package com.yumi.platform.http;

import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求编号过滤器。
 *
 * <p>为每个请求确定唯一请求编号（优先使用客户端 `X-Request-Id`），写入请求属性并回写响应头，
 * 使成功与失败响应都携带同一编号。请求编号只用于审计与问题定位，不代表认证。
 *
 * <p>过滤器不解析幂等键，也不判断请求是命令还是查询；这些由
 * {@link HttpRequestContextResolver} 在具体适配器中显式声明。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    /** 请求属性名：已解析的请求编号。 */
    public static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HttpHeaders.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = "req-" + UUID.randomUUID();
        }
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(HttpHeaders.REQUEST_ID, requestId);
        RequestCorrelationHolder.set(requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestCorrelationHolder.clear();
        }
    }
}
