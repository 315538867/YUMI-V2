package com.yumi.platform.authorization.application.port;

import java.time.Instant;
import java.util.Set;

/** 角色持久化记录（平台内部类型）。 */
public record RoleRecord(
        long id,
        String roleBusinessId,
        String roleCode,
        String name,
        String status,
        long rowVersion,
        Set<String> permissionCodes,
        Instant createdAt) {
}
