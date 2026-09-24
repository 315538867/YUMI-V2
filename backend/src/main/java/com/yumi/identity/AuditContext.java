package com.yumi.identity;

import com.yumi.shared.web.RequestIdFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

/**
 * 当前管理员审计上下文：操作人、请求 ID、幂等键与服务端时间。
 * 供写命令在业务事务内读取并落库到审计/业务表。
 */
@Component
public class AuditContext {

    public record Context(String adminUsername, String requestId, String idempotencyKey, Instant serverTime) {
    }

    public Context current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var adminUsername = authentication == null
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken
                ? null
                : authentication.getName();
        var request = currentRequest();
        var idempotencyKey = request.getHeader("Idempotency-Key");
        return new Context(adminUsername, RequestIdFilter.currentId(request), idempotencyKey, Instant.now());
    }

    private jakarta.servlet.http.HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        throw new IllegalStateException("当前不存在请求上下文，无法读取审计信息");
    }
}
