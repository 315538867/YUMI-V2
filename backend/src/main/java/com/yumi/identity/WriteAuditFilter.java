package com.yumi.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.shared.web.RequestIdFilter;
import com.yumi.shared.web.WritePolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 写命令审计：为 /api/* 写请求记录管理员、请求 ID、幂等键、服务端时间与结果。
 * 在业务事务之外落库，失败请求保留安全审计；审计写入自身失败不阻断业务响应。
 */
public class WriteAuditFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Logger log = LoggerFactory.getLogger(WriteAuditFilter.class);

    private final WriteAuditRepository repository;
    private final ObjectMapper objectMapper;

    public WriteAuditFilter(WriteAuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WRITE_METHODS.contains(request.getMethod())
                || !request.getRequestURI().startsWith("/api/")
                || WritePolicy.isReadOnlyPreview(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var cachedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, cachedResponse);

        var status = cachedResponse.getStatus();
        var body = cachedResponse.getContentAsByteArray();
        var success = status >= 200 && status < 300;
        try {
            repository.insert(new WriteAuditRepository.Entry(
                    currentAdmin(),
                    RequestIdFilter.currentId(request),
                    request.getHeader("Idempotency-Key"),
                    null,
                    request.getMethod(),
                    request.getRequestURI(),
                    success ? "SUCCESS" : "FAILURE",
                    status,
                    success ? null : errorCodeOf(body)));
        } catch (Exception auditFailure) {
            log.error("审计记录写入失败 requestId={}", RequestIdFilter.currentId(request), auditFailure);
        }
        cachedResponse.copyBodyToResponse();
    }

    private String currentAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    private String errorCodeOf(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            var code = objectMapper.readTree(body).path("code");
            return code.isMissingNode() || code.isNull() || code.asText().isBlank() ? null : code.asText();
        } catch (IOException notJson) {
            return null;
        }
    }
}
