package com.yumi.platform.identity.infrastructure;

import com.yumi.platform.api.identity.CurrentActor;

/**
 * 会话令牌解析端口：把令牌转换为当前操作者。
 *
 * <p>由 {@code IdentityApplicationService} 实现；过滤器只依赖本接口，避免直接依赖应用服务类。
 */
public interface IdentityActorResolver {

    CurrentActor requireCurrentActor(String sessionToken);
}
