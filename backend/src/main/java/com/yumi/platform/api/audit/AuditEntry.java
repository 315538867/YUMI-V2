package com.yumi.platform.api.audit;

import java.time.Instant;
import java.util.Map;

/** 审计记录视图；只读，不提供修改入口。 */
public record AuditEntry(
        String auditBusinessId,
        String requestId,
        String accountBusinessId,
        String moduleCode,
        String actionCode,
        String targetType,
        String targetBusinessId,
        String resultCode,
        String noteOfAuditBusinessId,
        Map<String, String> detail,
        Instant occurredAt) {
}
