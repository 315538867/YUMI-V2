package com.yumi.platform.identity.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 会话持久化端口。 */
public interface SessionRepository {

    void insert(SessionRecord record);

    Optional<SessionRecord> findByTokenHash(String tokenHash);

    Optional<SessionRecord> findByBusinessId(String sessionBusinessId);

    List<SessionRecord> findByAccount(String accountBusinessId);

    boolean revoke(String sessionBusinessId, Instant revokedAt);

    int revokeAllActiveOfAccount(String accountBusinessId, Instant revokedAt);

    int markExpired(Instant now);
}
