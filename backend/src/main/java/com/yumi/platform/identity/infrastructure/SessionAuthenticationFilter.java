package com.yumi.platform.identity.infrastructure;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.identity.CurrentActor;
import com.yumi.platform.api.identity.CurrentActorProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 会话认证过滤器：把 {@code Authorization: Bearer <token>} 解析为当前操作者。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§10。认证只校验会话有效性并装载权限；
 * 是否允许某个操作由权限点与业务规则决定。未携带令牌时不自动登录，也不使用任何默认账号。
 *
 * <p>过滤器不拒绝匿名请求：公开查询与登录端点需要匿名可访问；需要身份的端点由
 * {@link CurrentActorProvider#requireCurrentActor()} 与权限校验拒绝。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final IdentityActorResolver identityQuery;

    public SessionAuthenticationFilter(IdentityActorResolver identityQuery) {
        this.identityQuery = identityQuery;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (token != null) {
                try {
                    CurrentActor actor = identityQuery.requireCurrentActor(token);
                    CurrentActorHolder.set(actor);
                } catch (ApiException rejected) {
                    // 会话无效：记录具体原因（停用/吊销/过期），由后续权限校验原样拒绝
                    CurrentActorHolder.clear();
                    CurrentActorHolder.setRejection(rejected);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            CurrentActorHolder.clear();
        }
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
    }

    /** 当前操作者提供者：从请求级持有者读取，不信任任何客户端身份声明。 */
    public static class HolderBasedActorProvider implements CurrentActorProvider {

        @Override
        public CurrentActor requireCurrentActor() {
            CurrentActor actor = CurrentActorHolder.get();
            if (actor == null) {
                var rejection = CurrentActorHolder.getRejection();
                if (rejection != null) {
                    throw rejection;
                }
                throw new ApiException(
                        ApiErrorType.FORBIDDEN, "AUTHENTICATION_REQUIRED", "会话无效，请重新登录");
            }
            return actor;
        }
    }
}
