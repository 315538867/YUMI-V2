package com.yumi.platform.api.identity;

import com.yumi.platform.api.value.StableBusinessId;
import java.time.Instant;

/**
 * 登录成功后返回的会话引用。
 *
 * <p>{@code token} 是明文令牌，只在登录响应中出现一次；服务端只保存其哈希。
 */
public record SessionRef(
        String token,
        StableBusinessId sessionBusinessId,
        StableBusinessId accountBusinessId,
        Instant expiresAt) {
}
