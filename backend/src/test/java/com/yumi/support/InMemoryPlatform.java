package com.yumi.support;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.AccountRef;
import com.yumi.platform.api.identity.AccountStatus;
import com.yumi.platform.api.identity.CreateAccountCommand;
import com.yumi.platform.api.identity.CurrentActor;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.identity.IdentityApplicationService;
import com.yumi.platform.api.identity.IdentityQuery;
import com.yumi.platform.api.identity.SessionRef;
import com.yumi.platform.api.identity.SessionStatus;
import com.yumi.platform.authorization.application.PermissionService;
import com.yumi.platform.authorization.application.port.RoleRecord;
import com.yumi.platform.authorization.application.port.RoleRepository;
import com.yumi.platform.identity.application.port.AccountRecord;
import com.yumi.platform.identity.application.port.AccountRepository;
import com.yumi.platform.identity.application.port.PasswordHasher;
import com.yumi.platform.identity.application.port.SessionRecord;
import com.yumi.platform.identity.application.port.SessionRepository;
import com.yumi.platform.identity.application.port.TokenHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 身份与授权的内存测试替身。
 *
 * <p><b>用途</b>：仅在单元测试中验证应用层判定与错误语义。它<b>不</b>替代真实 MySQL 持久化、
 * HTTP 绑定、持久幂等与并发验收；依赖本类的测试不得被称为“真实登录/幂等已通过”。
 *
 * <p>当前会话模拟：测试显式调用 {@link #loginAs(String, String)} 建立会话后，由
 * {@link CurrentActorProvider} 返回该会话的操作者，模拟真实实现从 HTTP 会话解析身份。
 */
public final class InMemoryPlatform {

    private final MutableClock clock;

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();

    private final InMemoryRoleRepository roles = new InMemoryRoleRepository();

    private final PasswordHasher passwordHasher = new BcryptHasher();

    private final TokenHasher tokenHasher = new Sha256TokenHasher();

    private final com.yumi.platform.identity.application.IdentityApplicationService identity;

    private final PermissionService permissions;

    /** 当前请求的会话令牌，模拟 HTTP 会话；为空表示未认证。 */
    private String currentToken;

    public InMemoryPlatform(Clock clock) {
        this.clock = new MutableClock(clock);
        this.identity = new com.yumi.platform.identity.application.IdentityApplicationService(
                accounts, sessions, roles, passwordHasher, tokenHasher, this.clock);
        CurrentActorProvider provider = () -> {
            if (currentToken == null) {
                throw new ApiException(
                        ApiErrorType.FORBIDDEN, "AUTHENTICATION_REQUIRED", "会话无效，请重新登录");
            }
            return identity.requireCurrentActor(currentToken);
        };
        this.permissions = new PermissionService(provider, roles);
    }

    public IdentityApplicationService identityApplicationService() {
        return identity;
    }

    public IdentityQuery identityQuery() {
        return identity;
    }

    public AuthorizationService authorizationService() {
        return permissions;
    }

    public com.yumi.platform.identity.application.IdentityApplicationService identityService() {
        return identity;
    }

    public void advanceClock(Duration duration) {
        clock.advance(duration);
    }

    /** 登录并把该会话设为当前请求会话。 */
    public SessionRef loginAs(String loginName, String rawPassword) {
        SessionRef session = identity.login(new com.yumi.platform.api.identity.LoginCommand(loginName, rawPassword));
        currentToken = session.token();
        return session;
    }

    /** 直接设置当前请求会话，用于验证退出/停用后的行为。 */
    public void actAs(String rawToken) {
        currentToken = rawToken;
    }

    public void clearCurrentSession() {
        currentToken = null;
    }

    /** 空系统引导：创建管理员账号与拥有全部权限的内置角色。 */
    public AccountRef createInitialAdministrator(String loginName, String rawPassword) {
        ensureRole("ADMIN", "系统管理员", PermissionCodes.all());
        return createAccountWithRole(loginName, rawPassword, "系统管理员", "ADMIN");
    }

    public AccountRef createAccountWithRole(
            String loginName, String rawPassword, String displayName, String roleCode) {
        AccountRef account = identity.createAccount(new CreateAccountCommand(loginName, rawPassword, displayName));
        roles.replaceAccountRoles(account.accountBusinessId().value(), Set.of(roleCode), clock.instant(), "test");
        return account;
    }

    public void ensureRole(String roleCode, String name, Set<String> permissionCodes) {
        if (roles.findByRoleCode(roleCode).isPresent()) {
            return;
        }
        roles.insert(new RoleRecord(
                0L, "ROLE-" + roleCode, roleCode, name, "ACTIVE", 0L, permissionCodes, clock.instant()));
    }

    public void grantRolePermission(String roleCode, String permissionCode) {
        RoleRecord role = roles.findByRoleCode(roleCode).orElseThrow();
        Set<String> updated = new HashSet<>(role.permissionCodes());
        updated.add(permissionCode);
        roles.updateRole(role.roleBusinessId(), role.name(), updated, role.rowVersion(), clock.instant(), "test");
    }

    public void revokeRolePermission(String roleCode, String permissionCode) {
        RoleRecord role = roles.findByRoleCode(roleCode).orElseThrow();
        Set<String> updated = new HashSet<>(role.permissionCodes());
        updated.remove(permissionCode);
        roles.updateRole(role.roleBusinessId(), role.name(), updated, role.rowVersion(), clock.instant(), "test");
    }

    public RoleRecord role(String roleCode) {
        return roles.findByRoleCode(roleCode).orElseThrow();
    }

    public List<SessionView> sessionsOf(String accountBusinessId) {
        return sessions.findByAccount(accountBusinessId).stream()
                .map(session -> new SessionView(
                        session.sessionBusinessId(), session.status(), session.expiresAt(), session.revokedAt()))
                .toList();
    }

    public List<String> allStoredTokenHashes() {
        return sessions.allTokenHashes();
    }

    public CurrentActor currentActor() {
        if (currentToken == null) {
            throw new IllegalStateException("当前没有活动会话");
        }
        return identity.requireCurrentActor(currentToken);
    }

    /** 会话只读视图，不含令牌。 */
    public record SessionView(
            String sessionBusinessId, SessionStatus status, Instant expiresAt, Instant revokedAt) {

        public boolean active() {
            return status == SessionStatus.ACTIVE && revokedAt == null;
        }
    }

    /** 可推进的固定起点时钟。 */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Clock base) {
            this.instant = base.instant();
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final class BcryptHasher implements PasswordHasher {

        private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

        @Override
        public String hash(String rawPassword) {
            return encoder.encode(rawPassword);
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return encoder.matches(rawPassword, passwordHash);
        }
    }

    static final class Sha256TokenHasher implements TokenHasher {

        @Override
        public String hash(String rawToken) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("缺少 SHA-256 算法", exception);
            }
        }
    }

    static final class InMemoryAccountRepository implements AccountRepository {

        private final AtomicLong sequence = new AtomicLong();

        private final List<AccountRecord> records = new ArrayList<>();

        @Override
        public synchronized Optional<AccountRecord> findByLoginName(String loginName) {
            return records.stream().filter(record -> record.loginName().equals(loginName)).findFirst();
        }

        @Override
        public synchronized Optional<AccountRecord> findByBusinessId(String accountBusinessId) {
            return records.stream()
                    .filter(record -> record.accountBusinessId().equals(accountBusinessId))
                    .findFirst();
        }

        @Override
        public synchronized boolean existsAny() {
            return !records.isEmpty();
        }

        @Override
        public synchronized long count() {
            return records.size();
        }

        @Override
        public synchronized void insert(AccountRecord record, Instant now, String operatorId) {
            records.add(new AccountRecord(
                    sequence.incrementAndGet(),
                    record.accountBusinessId(),
                    record.loginName(),
                    record.displayName(),
                    record.passwordHash(),
                    record.status(),
                    record.failedLoginCount(),
                    1L,
                    now,
                    operatorId,
                    now,
                    operatorId));
        }

        @Override
        public synchronized boolean updateStatus(
                String accountBusinessId, AccountStatus status, long expectedRowVersion, Instant now,
                String operatorId) {
            for (int index = 0; index < records.size(); index++) {
                AccountRecord record = records.get(index);
                if (record.accountBusinessId().equals(accountBusinessId)) {
                    if (record.rowVersion() != expectedRowVersion) {
                        return false;
                    }
                    records.set(index, new AccountRecord(
                            record.id(), record.accountBusinessId(), record.loginName(), record.displayName(),
                            record.passwordHash(), status, record.failedLoginCount(),
                            record.rowVersion() + 1, record.createdAt(), record.createdBy(), now, operatorId));
                    return true;
                }
            }
            return false;
        }

        @Override
        public synchronized boolean updatePasswordHash(
                String accountBusinessId, String passwordHash, long expectedRowVersion, Instant now,
                String operatorId) {
            for (int index = 0; index < records.size(); index++) {
                AccountRecord record = records.get(index);
                if (record.accountBusinessId().equals(accountBusinessId)) {
                    if (record.rowVersion() != expectedRowVersion) {
                        return false;
                    }
                    records.set(index, new AccountRecord(
                            record.id(), record.accountBusinessId(), record.loginName(), record.displayName(),
                            passwordHash, record.status(), record.failedLoginCount(),
                            record.rowVersion() + 1, record.createdAt(), record.createdBy(), now, operatorId));
                    return true;
                }
            }
            return false;
        }

        @Override
        public synchronized boolean updateFailedLoginCount(
                String accountBusinessId, int failedLoginCount, long expectedRowVersion, Instant now,
                String operatorId) {
            for (int index = 0; index < records.size(); index++) {
                AccountRecord record = records.get(index);
                if (record.accountBusinessId().equals(accountBusinessId)) {
                    if (record.rowVersion() != expectedRowVersion) {
                        return false;
                    }
                    records.set(index, new AccountRecord(
                            record.id(), record.accountBusinessId(), record.loginName(), record.displayName(),
                            record.passwordHash(), record.status(), failedLoginCount,
                            record.rowVersion() + 1, record.createdAt(), record.createdBy(), now, operatorId));
                    return true;
                }
            }
            return false;
        }

        @Override
        public synchronized List<AccountRecord> findAllOrderByCreatedAt() {
            return records.stream().sorted(Comparator.comparing(AccountRecord::createdAt)).toList();
        }
    }

    static final class InMemorySessionRepository implements SessionRepository {

        private final AtomicLong sequence = new AtomicLong();

        private final List<SessionRecord> records = new ArrayList<>();

        @Override
        public synchronized void insert(SessionRecord record) {
            records.add(new SessionRecord(
                    sequence.incrementAndGet(),
                    record.sessionBusinessId(),
                    record.accountBusinessId(),
                    record.tokenHash(),
                    record.status(),
                    record.expiresAt(),
                    record.revokedAt(),
                    record.createdAt()));
        }

        @Override
        public synchronized Optional<SessionRecord> findByTokenHash(String tokenHash) {
            return records.stream().filter(record -> record.tokenHash().equals(tokenHash)).findFirst();
        }

        @Override
        public synchronized Optional<SessionRecord> findByBusinessId(String sessionBusinessId) {
            return records.stream()
                    .filter(record -> record.sessionBusinessId().equals(sessionBusinessId))
                    .findFirst();
        }

        @Override
        public synchronized List<SessionRecord> findByAccount(String accountBusinessId) {
            return records.stream()
                    .filter(record -> record.accountBusinessId().equals(accountBusinessId))
                    .sorted(Comparator.comparingLong(SessionRecord::id))
                    .toList();
        }

        @Override
        public synchronized boolean revoke(String sessionBusinessId, Instant revokedAt) {
            for (int index = 0; index < records.size(); index++) {
                SessionRecord record = records.get(index);
                if (record.sessionBusinessId().equals(sessionBusinessId)) {
                    records.set(index, new SessionRecord(
                            record.id(), record.sessionBusinessId(), record.accountBusinessId(),
                            record.tokenHash(), SessionStatus.REVOKED, record.expiresAt(), revokedAt,
                            record.createdAt()));
                    return true;
                }
            }
            return false;
        }

        @Override
        public synchronized int revokeAllActiveOfAccount(String accountBusinessId, Instant revokedAt) {
            int revoked = 0;
            for (int index = 0; index < records.size(); index++) {
                SessionRecord record = records.get(index);
                if (record.accountBusinessId().equals(accountBusinessId)
                        && record.status() == SessionStatus.ACTIVE
                        && record.revokedAt() == null) {
                    records.set(index, new SessionRecord(
                            record.id(), record.sessionBusinessId(), record.accountBusinessId(),
                            record.tokenHash(), SessionStatus.REVOKED, record.expiresAt(), revokedAt,
                            record.createdAt()));
                    revoked++;
                }
            }
            return revoked;
        }

        @Override
        public synchronized int markExpired(Instant now) {
            int expired = 0;
            for (int index = 0; index < records.size(); index++) {
                SessionRecord record = records.get(index);
                if (record.status() == SessionStatus.ACTIVE && !record.expiresAt().isAfter(now)) {
                    records.set(index, new SessionRecord(
                            record.id(), record.sessionBusinessId(), record.accountBusinessId(),
                            record.tokenHash(), SessionStatus.EXPIRED, record.expiresAt(), record.revokedAt(),
                            record.createdAt()));
                    expired++;
                }
            }
            return expired;
        }

        synchronized List<String> allTokenHashes() {
            return records.stream().map(SessionRecord::tokenHash).toList();
        }
    }

    static final class InMemoryRoleRepository implements RoleRepository {

        private final Map<String, RoleRecord> byBusinessId = new LinkedHashMap<>();

        private final Map<String, Set<String>> accountRoles = new HashMap<>();

        private final Map<String, String> permissionNames = new HashMap<>();

        @Override
        public synchronized Optional<RoleRecord> findByRoleCode(String roleCode) {
            return byBusinessId.values().stream()
                    .filter(record -> record.roleCode().equals(roleCode))
                    .findFirst();
        }

        @Override
        public synchronized Optional<RoleRecord> findByBusinessId(String roleBusinessId) {
            return Optional.ofNullable(byBusinessId.get(roleBusinessId));
        }

        @Override
        public synchronized List<RoleRecord> findAll() {
            return List.copyOf(byBusinessId.values());
        }

        @Override
        public synchronized void insert(RoleRecord record) {
            byBusinessId.put(
                    record.roleBusinessId(),
                    new RoleRecord(
                            byBusinessId.size() + 1L,
                            record.roleBusinessId(),
                            record.roleCode(),
                            record.name(),
                            record.status(),
                            1L,
                            Set.copyOf(record.permissionCodes()),
                            record.createdAt()));
        }

        @Override
        public synchronized boolean updateRole(
                String roleBusinessId, String name, Set<String> permissionCodes, long expectedRowVersion,
                Instant now, String operatorId) {
            RoleRecord record = byBusinessId.get(roleBusinessId);
            if (record == null || record.rowVersion() != expectedRowVersion) {
                return false;
            }
            byBusinessId.put(roleBusinessId, new RoleRecord(
                    record.id(), record.roleBusinessId(), record.roleCode(), name, record.status(),
                    record.rowVersion() + 1, Set.copyOf(permissionCodes), record.createdAt()));
            return true;
        }

        @Override
        public synchronized Set<String> findRoleCodesOfAccount(String accountBusinessId) {
            Set<String> codes = new LinkedHashSet<>();
            for (String roleBusinessId : accountRoles.getOrDefault(accountBusinessId, Set.of())) {
                RoleRecord record = byBusinessId.get(roleBusinessId);
                if (record != null) {
                    codes.add(record.roleCode());
                }
            }
            return codes;
        }

        @Override
        public synchronized Set<String> findPermissionsOfAccount(String accountBusinessId) {
            Set<String> permissions = new LinkedHashSet<>();
            for (String roleBusinessId : accountRoles.getOrDefault(accountBusinessId, Set.of())) {
                RoleRecord record = byBusinessId.get(roleBusinessId);
                if (record != null) {
                    permissions.addAll(record.permissionCodes());
                }
            }
            return permissions;
        }

        @Override
        public synchronized void replaceAccountRoles(
                String accountBusinessId, Set<String> roleCodes, Instant now, String operatorId) {
            Set<String> roleBusinessIds = new LinkedHashSet<>();
            for (String roleCode : roleCodes) {
                RoleRecord record = findByRoleCode(roleCode)
                        .orElseThrow(() -> new IllegalArgumentException("角色不存在：" + roleCode));
                roleBusinessIds.add(record.roleBusinessId());
            }
            accountRoles.put(accountBusinessId, roleBusinessIds);
        }

        @Override
        public synchronized List<String> findAccountBusinessIdsOfRole(String roleBusinessId) {
            return accountRoles.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(roleBusinessId))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        @Override
        public synchronized void upsertPermission(
                String permissionCode, String name, String resourceType) {
            permissionNames.put(permissionCode, name);
        }
    }
}
