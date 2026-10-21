package com.yumi.platform.identity.application.port;

import com.yumi.platform.api.identity.AccountStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 账号持久化端口：由基础设施适配；应用层不感知 SQL 与表名。 */
public interface AccountRepository {

    Optional<AccountRecord> findByLoginName(String loginName);

    Optional<AccountRecord> findByBusinessId(String accountBusinessId);

    boolean existsAny();

    long count();

    void insert(AccountRecord record, Instant now, String operatorId);

    /** 条件更新：仅当当前行版本等于期望值时成功。 */
    boolean updateStatus(
            String accountBusinessId, AccountStatus status, long expectedRowVersion, Instant now, String operatorId);

    boolean updatePasswordHash(
            String accountBusinessId, String passwordHash, long expectedRowVersion, Instant now, String operatorId);

    boolean updateFailedLoginCount(
            String accountBusinessId, int failedLoginCount, long expectedRowVersion, Instant now, String operatorId);

    List<AccountRecord> findAllOrderByCreatedAt();
}
