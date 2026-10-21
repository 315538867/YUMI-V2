package com.yumi.platform.api.identity;

import com.yumi.platform.api.value.StableBusinessId;
import java.time.Instant;
import java.util.Set;

/** 账号视图：不含口令哈希与令牌。 */
public record AccountView(
        StableBusinessId accountBusinessId,
        String loginName,
        String displayName,
        AccountStatus status,
        long rowVersion,
        Set<String> roleCodes,
        Instant createdAt) {
}
