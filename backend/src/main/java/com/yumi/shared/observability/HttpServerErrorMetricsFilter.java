package com.yumi.shared.observability;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 5xx 计数（任务 9.5）：每个以 5xx 结束的请求累加一次
 * `yumi_http_server_errors_total{method,status,uri}`，供告警规则使用。
 * 只计数、不改写响应，也不吞异常——异常仍按既有错误信封返回。
 */
@Component
public class HttpServerErrorMetricsFilter extends OncePerRequestFilter {

    /** 指标名（Prometheus 暴露为 `yumi_http_server_errors_total`）。 */
    public static final String METRIC = "yumi.http.server.errors";
    /** 标签 `uri` 只取**路由模板**，避免把订单号等业务标识写进指标造成高基数。 */
    private static final String UNKNOWN_ROUTE = "UNKNOWN";

    private final MeterRegistry registry;

    public HttpServerErrorMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            if (response.getStatus() >= 500) {
                registry.counter(METRIC, "method", request.getMethod(), "status",
                        String.valueOf(response.getStatus()), "uri", routeOf(request)).increment();
            }
        }
    }

    private static String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
        return pattern == null ? UNKNOWN_ROUTE : pattern.toString();
    }
}
