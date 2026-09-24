package com.yumi.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个请求分配或透传 X-Request-Id：合法入参沿用，否则重新生成；
 * 同时写入请求属性、响应头与 MDC，供错误体和日志共用。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "yumi.requestId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var inbound = request.getHeader(HEADER);
        var requestId = inbound != null && VALID.matcher(inbound).matches()
                ? inbound
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    public static String currentId(HttpServletRequest request) {
        var attribute = request.getAttribute(ATTRIBUTE);
        return attribute != null ? attribute.toString() : UUID.randomUUID().toString();
    }
}
