package com.yumi.platform.identity.infrastructure;

import com.yumi.platform.api.identity.AccountStatus;
import com.yumi.platform.identity.application.port.AccountRecord;
import com.yumi.platform.identity.application.port.AccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 账号持久化（MySQL）。
 *
 * <p>所有状态变更使用“期望行版本”条件更新：影响行数为 0 表示并发冲突，由应用层转为稳定错误，
 * 不做静默覆盖。时间统一按 UTC 存储（{@code DATETIME(6)}）。
 */
@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountRecord> findByLoginName(String loginName) {
        return jdbc.sql(SELECT + " WHERE login_name = :loginName")
                .param("loginName", loginName)
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<AccountRecord> findByBusinessId(String accountBusinessId) {
        return jdbc.sql(SELECT + " WHERE account_business_id = :businessId")
                .param("businessId", accountBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public boolean existsAny() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM platform_accounts)")
                .query(Boolean.class)
                .single();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM platform_accounts").query(Long.class).single();
    }

    @Override
    public void insert(AccountRecord record, Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO platform_accounts (
                            account_business_id, login_name, display_name, password_hash, status,
                            failed_login_count, row_version, created_at, created_by, updated_at, updated_by)
                        VALUES (:businessId, :loginName, :displayName, :passwordHash, :status,
                                :failedLoginCount, :rowVersion, :now, :operatorId, :now, :operatorId)
                        """)
                .param("businessId", record.accountBusinessId())
                .param("loginName", record.loginName())
                .param("displayName", record.displayName())
                .param("passwordHash", record.passwordHash())
                .param("status", record.status().name())
                .param("failedLoginCount", record.failedLoginCount())
                .param("rowVersion", 1L)
                .param("now", LocalDateTime.ofInstant(now, UTC))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public boolean updateStatus(
            String accountBusinessId, AccountStatus status, long expectedRowVersion, Instant now,
            String operatorId) {
        return jdbc.sql("""
                        UPDATE platform_accounts
                        SET status = :status, row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE account_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("status", status.name())
                .param("now", LocalDateTime.ofInstant(now, UTC))
                .param("operatorId", operatorId)
                .param("businessId", accountBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public boolean updatePasswordHash(
            String accountBusinessId, String passwordHash, long expectedRowVersion, Instant now,
            String operatorId) {
        return jdbc.sql("""
                        UPDATE platform_accounts
                        SET password_hash = :passwordHash, row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE account_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("passwordHash", passwordHash)
                .param("now", LocalDateTime.ofInstant(now, UTC))
                .param("operatorId", operatorId)
                .param("businessId", accountBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public boolean updateFailedLoginCount(
            String accountBusinessId, int failedLoginCount, long expectedRowVersion, Instant now,
            String operatorId) {
        return jdbc.sql("""
                        UPDATE platform_accounts
                        SET failed_login_count = :failedLoginCount, row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE account_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("failedLoginCount", failedLoginCount)
                .param("now", LocalDateTime.ofInstant(now, UTC))
                .param("operatorId", operatorId)
                .param("businessId", accountBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public List<AccountRecord> findAllOrderByCreatedAt() {
        return jdbc.sql(SELECT + " ORDER BY created_at, id").query(this::map).list();
    }

    private static final String SELECT = """
            SELECT id, account_business_id, login_name, display_name, password_hash, status,
                   failed_login_count, row_version, created_at, created_by, updated_at, updated_by
            FROM platform_accounts
            """;

    private AccountRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AccountRecord(
                resultSet.getLong("id"),
                resultSet.getString("account_business_id"),
                resultSet.getString("login_name"),
                resultSet.getString("display_name"),
                resultSet.getString("password_hash"),
                AccountStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("failed_login_count"),
                resultSet.getLong("row_version"),
                toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                resultSet.getString("created_by"),
                toInstant(resultSet.getObject("updated_at", LocalDateTime.class)),
                resultSet.getString("updated_by"));
    }

    /** 兼容旧调用：统一走 {@link com.yumi.platform.api.jdbc.JdbcTimes}。 */
    public static Instant toInstant(LocalDateTime value) {
        return com.yumi.platform.api.jdbc.JdbcTimes.toInstant(value);
    }
}
