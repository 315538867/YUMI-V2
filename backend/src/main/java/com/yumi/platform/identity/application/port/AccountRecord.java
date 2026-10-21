package com.yumi.platform.identity.application.port;

import com.yumi.platform.api.identity.AccountStatus;
import java.time.Instant;

/**
 * 账号持久化记录（平台内部类型，不跨模块暴露）。
 *
 * @param id 数据库内部主键，仅平台内部使用
 */
public record AccountRecord(
        long id,
        String accountBusinessId,
        String loginName,
        String displayName,
        String passwordHash,
        AccountStatus status,
        int failedLoginCount,
        long rowVersion,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {
}
