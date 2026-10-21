package com.yumi.platform.identity.application.port;

import com.yumi.platform.api.identity.SessionStatus;
import java.time.Instant;

/** 会话持久化记录（平台内部类型）。 */
public record SessionRecord(
        long id,
        String sessionBusinessId,
        String accountBusinessId,
        String tokenHash,
        SessionStatus status,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt) {

    public boolean isUsableAt(Instant now) {
        return status == SessionStatus.ACTIVE && revokedAt == null && expiresAt.isAfter(now);
    }
}
