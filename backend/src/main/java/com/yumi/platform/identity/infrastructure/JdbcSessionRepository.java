package com.yumi.platform.identity.infrastructure;

import com.yumi.platform.api.identity.SessionStatus;
import com.yumi.platform.identity.application.port.SessionRecord;
import com.yumi.platform.identity.application.port.SessionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 会话持久化（MySQL）；只保存令牌哈希。 */
@Repository
public class JdbcSessionRepository implements SessionRepository {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final JdbcClient jdbc;

    public JdbcSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(SessionRecord record) {
        jdbc.sql("""
                        INSERT INTO platform_sessions (
                            session_business_id, account_business_id, token_hash, status,
                            expires_at, revoked_at, created_at)
                        VALUES (:sessionBusinessId, :accountBusinessId, :tokenHash, :status,
                                :expiresAt, :revokedAt, :createdAt)
                        """)
                .param("sessionBusinessId", record.sessionBusinessId())
                .param("accountBusinessId", record.accountBusinessId())
                .param("tokenHash", record.tokenHash())
                .param("status", record.status().name())
                .param("expiresAt", LocalDateTime.ofInstant(record.expiresAt(), UTC))
                .param("revokedAt",
                        record.revokedAt() == null ? null : LocalDateTime.ofInstant(record.revokedAt(), UTC))
                .param("createdAt", LocalDateTime.ofInstant(record.createdAt(), UTC))
                .update();
    }

    @Override
    public Optional<SessionRecord> findByTokenHash(String tokenHash) {
        return jdbc.sql(SELECT + " WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<SessionRecord> findByBusinessId(String sessionBusinessId) {
        return jdbc.sql(SELECT + " WHERE session_business_id = :sessionBusinessId")
                .param("sessionBusinessId", sessionBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public List<SessionRecord> findByAccount(String accountBusinessId) {
        return jdbc.sql(SELECT + " WHERE account_business_id = :accountBusinessId ORDER BY id")
                .param("accountBusinessId", accountBusinessId)
                .query(this::map)
                .list();
    }

    @Override
    public boolean revoke(String sessionBusinessId, Instant revokedAt) {
        return jdbc.sql("""
                        UPDATE platform_sessions
                        SET status = 'REVOKED', revoked_at = :revokedAt
                        WHERE session_business_id = :sessionBusinessId AND status = 'ACTIVE'
                        """)
                .param("revokedAt", LocalDateTime.ofInstant(revokedAt, UTC))
                .param("sessionBusinessId", sessionBusinessId)
                .update() == 1;
    }

    @Override
    public int revokeAllActiveOfAccount(String accountBusinessId, Instant revokedAt) {
        return jdbc.sql("""
                        UPDATE platform_sessions
                        SET status = 'REVOKED', revoked_at = :revokedAt
                        WHERE account_business_id = :accountBusinessId AND status = 'ACTIVE'
                        """)
                .param("revokedAt", LocalDateTime.ofInstant(revokedAt, UTC))
                .param("accountBusinessId", accountBusinessId)
                .update();
    }

    @Override
    public int markExpired(Instant now) {
        return jdbc.sql("""
                        UPDATE platform_sessions
                        SET status = 'EXPIRED'
                        WHERE status = 'ACTIVE' AND expires_at <= :now
                        """)
                .param("now", LocalDateTime.ofInstant(now, UTC))
                .update();
    }

    private static final String SELECT = """
            SELECT id, session_business_id, account_business_id, token_hash, status,
                   expires_at, revoked_at, created_at
            FROM platform_sessions
            """;

    private SessionRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SessionRecord(
                resultSet.getLong("id"),
                resultSet.getString("session_business_id"),
                resultSet.getString("account_business_id"),
                resultSet.getString("token_hash"),
                SessionStatus.valueOf(resultSet.getString("status")),
                JdbcAccountRepository.toInstant(resultSet.getObject("expires_at", LocalDateTime.class)),
                JdbcAccountRepository.toInstant(resultSet.getObject("revoked_at", LocalDateTime.class)),
                JdbcAccountRepository.toInstant(resultSet.getObject("created_at", LocalDateTime.class)));
    }
}
