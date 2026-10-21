package com.yumi.platform.identity.application;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.AccountRef;
import com.yumi.platform.api.identity.AccountStatus;
import com.yumi.platform.api.identity.AccountView;
import com.yumi.platform.api.identity.ChangeAccountStatusCommand;
import com.yumi.platform.api.identity.CreateAccountCommand;
import com.yumi.platform.api.identity.CurrentActor;
import com.yumi.platform.api.identity.IdentityQuery;
import com.yumi.platform.api.identity.LoginCommand;
import com.yumi.platform.api.identity.LogoutCommand;
import com.yumi.platform.api.identity.SessionRef;
import com.yumi.platform.api.identity.SessionStatus;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.platform.authorization.application.port.RoleRepository;
import com.yumi.platform.identity.application.port.AccountRecord;
import com.yumi.platform.identity.application.port.AccountRepository;
import com.yumi.platform.identity.application.port.PasswordHasher;
import com.yumi.platform.identity.application.port.SessionRecord;
import com.yumi.platform.identity.application.port.SessionRepository;
import com.yumi.platform.identity.application.port.TokenHasher;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 身份应用服务：登录、退出、会话校验、账号创建与状态变更。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§6、§9、§10：
 * <ul>
 *   <li>账号与员工资料相互独立，本服务不读写任何员工数据；</li>
 *   <li>登录失败不区分“账号不存在”与“口令错误”，避免账号枚举；</li>
 *   <li>口令只保存强哈希，令牌只保存哈希；</li>
 *   <li>账号停用立即吊销其全部活动会话；</li>
 *   <li>修改账号状态必须匹配行版本，冲突返回并发错误而不静默覆盖。</li>
 * </ul>
 *
 * <p>本类不做 HTTP 映射与持久幂等记录；这些由适配层与 2.4 的幂等服务在同事务中完成。
 */
public class IdentityApplicationService
        implements com.yumi.platform.api.identity.IdentityApplicationService, IdentityQuery,
                   com.yumi.platform.identity.infrastructure.IdentityActorResolver {

    /** 连续登录失败达到该次数后锁定账号。 */
    public static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;

    /** 会话有效期；属于部署参数，一期默认 12 小时。 */
    public static final Duration SESSION_TTL = Duration.ofHours(12);

    /** 口令最小长度；部署参数可以更严，不得更宽。 */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private static final Pattern LOGIN_NAME = Pattern.compile("[a-zA-Z0-9._-]{3,64}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;

    private final SessionRepository sessions;

    private final RoleRepository roles;

    private final PasswordHasher passwordHasher;

    private final TokenHasher tokenHasher;

    private final Clock clock;

    public IdentityApplicationService(
            AccountRepository accounts,
            SessionRepository sessions,
            RoleRepository roles,
            PasswordHasher passwordHasher,
            TokenHasher tokenHasher,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "账号仓储不能为空");
        this.sessions = Objects.requireNonNull(sessions, "会话仓储不能为空");
        this.roles = Objects.requireNonNull(roles, "角色仓储不能为空");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "口令哈希不能为空");
        this.tokenHasher = Objects.requireNonNull(tokenHasher, "令牌哈希不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    @Override
    public SessionRef login(LoginCommand command) {
        Objects.requireNonNull(command, "登录命令不能为空");
        Instant now = clock.instant();

        Optional<AccountRecord> found = accounts.findByLoginName(trimToEmpty(command.loginName()));
        if (found.isEmpty()) {
            throw invalidCredentials();
        }
        AccountRecord account = found.get();

        if (account.status() == AccountStatus.DISABLED) {
            throw new ApiException(ApiErrorType.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用");
        }
        if (account.status() == AccountStatus.LOCKED) {
            throw new ApiException(ApiErrorType.FORBIDDEN, "ACCOUNT_LOCKED", "账号已锁定，请联系管理员");
        }
        if (!passwordHasher.matches(nullToEmpty(command.rawPassword()), account.passwordHash())) {
            registerFailedLogin(account, now);
            throw invalidCredentials();
        }

        if (account.failedLoginCount() > 0) {
            accounts.updateFailedLoginCount(
                    account.accountBusinessId(), 0, account.rowVersion(), now, account.accountBusinessId());
        }

        String rawToken = newToken();
        String sessionBusinessId = "SES-" + UUID.randomUUID();
        SessionRecord session = new SessionRecord(
                0L,
                sessionBusinessId,
                account.accountBusinessId(),
                tokenHasher.hash(rawToken),
                SessionStatus.ACTIVE,
                now.plus(SESSION_TTL),
                null,
                now);
        sessions.insert(session);

        return new SessionRef(
                rawToken,
                StableBusinessId.of(sessionBusinessId),
                StableBusinessId.of(account.accountBusinessId()),
                session.expiresAt());
    }

    @Override
    public void logout(LogoutCommand command) {
        Objects.requireNonNull(command, "退出命令不能为空");
        String raw = nullToEmpty(command.sessionToken());
        if (raw.isBlank()) {
            return;
        }
        sessions.findByTokenHash(tokenHasher.hash(raw))
                .ifPresent(session -> sessions.revoke(session.sessionBusinessId(), clock.instant()));
    }

    @Override
    public AccountRef createAccount(CreateAccountCommand command) {
        Objects.requireNonNull(command, "创建账号命令不能为空");
        String loginName = trimToEmpty(command.loginName());
        if (!LOGIN_NAME.matcher(loginName).matches()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION,
                    "INVALID_LOGIN_NAME",
                    "登录名必须为 3—64 位字母、数字、点、下划线或连字符");
        }
        String rawPassword = nullToEmpty(command.initialPassword());
        if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(
                    ApiErrorType.VALIDATION,
                    "WEAK_PASSWORD",
                    "初始口令长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (accounts.findByLoginName(loginName).isPresent()) {
            throw new ApiException(ApiErrorType.BUSINESS_REJECTION, "LOGIN_NAME_TAKEN", "登录名已被占用");
        }

        Instant now = clock.instant();
        String accountBusinessId = "ACC-" + UUID.randomUUID();
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? loginName
                : command.displayName().strip();
        accounts.insert(
                new AccountRecord(
                        0L,
                        accountBusinessId,
                        loginName,
                        displayName,
                        passwordHasher.hash(rawPassword),
                        AccountStatus.ACTIVE,
                        0,
                        0L,
                        now,
                        accountBusinessId,
                        now,
                        accountBusinessId),
                now,
                accountBusinessId);

        return new AccountRef(StableBusinessId.of(accountBusinessId), loginName, AccountStatus.ACTIVE, 1L);
    }

    @Override
    public AccountRef changeAccountStatus(ChangeAccountStatusCommand command) {
        Objects.requireNonNull(command, "账号状态命令不能为空");
        Objects.requireNonNull(command.targetStatus(), "目标状态不能为空");
        String businessId = command.accountBusinessId().value();
        Instant now = clock.instant();

        accounts.findByBusinessId(businessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ACCOUNT_NOT_FOUND", "账号不存在"));

        if (!accounts.updateStatus(
                businessId, command.targetStatus(), command.expectedRowVersion(), now, businessId)) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT,
                    "ROW_VERSION_CONFLICT",
                    "账号已被其他操作修改，请刷新后重试");
        }

        if (command.targetStatus() != AccountStatus.ACTIVE) {
            sessions.revokeAllActiveOfAccount(businessId, now);
        }

        AccountRecord updated = accounts.findByBusinessId(businessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ACCOUNT_NOT_FOUND", "账号不存在"));
        return new AccountRef(
                StableBusinessId.of(updated.accountBusinessId()), updated.loginName(), updated.status(),
                updated.rowVersion());
    }

    @Override
    public CurrentActor requireCurrentActor(String sessionToken) {
        String raw = nullToEmpty(sessionToken);
        if (raw.isBlank()) {
            throw notAuthenticated();
        }
        String tokenHash = tokenHasher.hash(raw);
        Instant now = clock.instant();

        SessionRecord session = sessions.findByTokenHash(tokenHash)
                .orElseThrow(IdentityApplicationService::notAuthenticated);
        AccountRecord account = accounts.findByBusinessId(session.accountBusinessId())
                .orElseThrow(IdentityApplicationService::notAuthenticated);

        if (account.status() == AccountStatus.DISABLED) {
            throw new ApiException(ApiErrorType.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用");
        }
        if (account.status() == AccountStatus.LOCKED) {
            throw new ApiException(ApiErrorType.FORBIDDEN, "ACCOUNT_LOCKED", "账号已锁定，请联系管理员");
        }
        if (session.status() == SessionStatus.REVOKED || session.revokedAt() != null) {
            throw new ApiException(ApiErrorType.FORBIDDEN, "SESSION_REVOKED", "会话已失效，请重新登录");
        }
        if (!session.expiresAt().isAfter(now)) {
            sessions.revoke(session.sessionBusinessId(), now);
            throw new ApiException(ApiErrorType.FORBIDDEN, "SESSION_EXPIRED", "会话已过期，请重新登录");
        }

        Set<String> permissions = roles.findPermissionsOfAccount(account.accountBusinessId());
        return new CurrentActor(
                StableBusinessId.of(account.accountBusinessId()),
                account.loginName(),
                StableBusinessId.of(session.sessionBusinessId()),
                permissions);
    }

    @Override
    public AccountView getAccount(StableBusinessId accountBusinessId) {
        AccountRecord account = loadAccount(accountBusinessId.value());
        return new AccountView(
                StableBusinessId.of(account.accountBusinessId()),
                account.loginName(),
                account.displayName(),
                account.status(),
                account.rowVersion(),
                roles.findRoleCodesOfAccount(account.accountBusinessId()),
                account.createdAt());
    }

    /** 读取账号记录（平台内部使用，不跨模块暴露）。 */
    public AccountRecord loadAccount(String accountBusinessId) {
        return accounts.findByBusinessId(accountBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ACCOUNT_NOT_FOUND", "账号不存在"));
    }

    /** 系统中是否已有账号：首次管理员引导的空系统守卫。 */
    public boolean systemHasAccounts() {
        return accounts.existsAny();
    }

    /** 账号总数，用于空系统与并发引导判断。 */
    public long accountCount() {
        return accounts.count();
    }

    /** 全部账号（按创建时间），仅平台内部管理使用。 */
    public List<AccountRecord> allAccounts() {
        return accounts.findAllOrderByCreatedAt();
    }

    private void registerFailedLogin(AccountRecord account, Instant now) {
        int attempts = account.failedLoginCount() + 1;
        if (attempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
            accounts.updateStatus(
                    account.accountBusinessId(), AccountStatus.LOCKED, account.rowVersion(), now,
                    account.accountBusinessId());
            sessions.revokeAllActiveOfAccount(account.accountBusinessId(), now);
            return;
        }
        accounts.updateFailedLoginCount(
                account.accountBusinessId(), attempts, account.rowVersion(), now, account.accountBusinessId());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ApiErrorType.FORBIDDEN, "INVALID_CREDENTIALS", "登录名或密码不正确");
    }

    private static ApiException notAuthenticated() {
        return new ApiException(ApiErrorType.FORBIDDEN, "AUTHENTICATION_REQUIRED", "会话无效，请重新登录");
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
