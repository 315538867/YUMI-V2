package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.authorization.AssignAccountRolesCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.RoleView;
import com.yumi.platform.api.authorization.SaveRoleCommand;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.AccountRef;
import com.yumi.platform.api.identity.AccountStatus;
import com.yumi.platform.api.identity.ChangeAccountStatusCommand;
import com.yumi.platform.api.identity.CreateAccountCommand;
import com.yumi.platform.api.identity.CurrentActor;
import com.yumi.platform.api.identity.LoginCommand;
import com.yumi.platform.api.identity.SessionRef;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.platform.authorization.application.PermissionService;
import com.yumi.platform.authorization.application.RoleAdministrationService;
import com.yumi.platform.authorization.infrastructure.JdbcRoleRepository;
import com.yumi.platform.identity.application.AccountAdministrationService;
import com.yumi.platform.identity.application.IdentityApplicationService;
import com.yumi.platform.identity.bootstrap.InitialAdministratorCommand;
import com.yumi.platform.identity.infrastructure.CurrentActorHolder;
import com.yumi.platform.identity.infrastructure.JdbcAccountRepository;
import com.yumi.platform.identity.infrastructure.JdbcSessionRepository;
import com.yumi.platform.identity.infrastructure.SessionAuthenticationFilter;
import com.yumi.support.AbstractMySqlIntegrationTest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 平台管理：账号创建/停用、角色维护、账号授权、首次管理员引导与审计（PL-01、PL-02）。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9、§10、§12。使用真实 MySQL，
 * 不使用未来业务模块的数据；身份会话用显式操作者上下文模拟，真实 HTTP 认证在 2.11 验证。
 */
class PlatformAdministrationIT extends AbstractMySqlIntegrationTest {

    private static final StableBusinessId SYSTEM_ACTOR = StableBusinessId.of("ACC-SYSTEM-TEST");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private javax.sql.DataSource dataSource;

    private com.yumi.platform.identity.application.IdentityApplicationService identity;

    private AccountAdministrationService accounts;

    private RoleAdministrationService roles;

    private PermissionService permissions;


    private TransactionTemplate transactionTemplate;

    private InitialAdministratorCommand bootstrap;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM platform_account_roles").update();
        jdbc.sql("DELETE FROM platform_role_permissions").update();
        jdbc.sql("DELETE FROM platform_sessions").update();
        jdbc.sql("DELETE FROM platform_accounts").update();
        jdbc.sql("DELETE FROM platform_roles").update();
        jdbc.sql("DELETE FROM platform_permissions").update();
        jdbc.sql("DELETE FROM platform_audit_logs").update();

        Clock clock = Clock.systemUTC();
        var accountRepository = new JdbcAccountRepository(jdbc);
        var sessionRepository = new JdbcSessionRepository(jdbc);
        var roleRepository = new JdbcRoleRepository(jdbc);
        identity = new com.yumi.platform.identity.application.IdentityApplicationService(
                accountRepository, sessionRepository, roleRepository,
                new PlainBcryptHasher(), new Sha256Hasher(), clock);
        CurrentActorHolderActorProvider actorProvider = new CurrentActorHolderActorProvider();
        permissions = new PermissionService(actorProvider, roleRepository);
        var audit = new com.yumi.platform.audit.application.AuditService(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        accounts = new AccountAdministrationService(
                identity, roleRepository, permissions, actorProvider, audit, clock);
        roles = new RoleAdministrationService(roleRepository, permissions, actorProvider, clock, audit);
        bootstrap = new InitialAdministratorCommand(
                identity, roleRepository, jdbc, dataSource, transactionManager, clock);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("首次管理员引导：空系统原子创建账号与角色，口令不出现在返回值中")
    void bootstrapCreatesAdministratorOnce() {
        AccountRef admin = bootstrap.initialize("root-admin", "secret-pass-1");

        assertThat(admin.loginName()).isEqualTo("root-admin");
        assertThat(admin.accountBusinessId().value()).isNotBlank();
        assertThat(admin.toString()).doesNotContain("secret-pass-1");

        // 初始化后即可登录，并拥有全部一期权限
        SessionRef session = identity.login(new LoginCommand("root-admin", "secret-pass-1"));
        CurrentActor actor = identity.requireCurrentActor(session.token());
        assertThat(actor.permissions()).contains(
                PermissionCodes.PLATFORM_ACCOUNT_MANAGE,
                PermissionCodes.PLATFORM_ROLE_MANAGE,
                PermissionCodes.PAYROLL_SENSITIVE_READ);

        // 口令只保存强哈希
        String storedHash = jdbc.sql("""
                        SELECT password_hash FROM platform_accounts WHERE login_name = 'root-admin'
                        """).query(String.class).single();
        assertThat(storedHash).startsWith("$2").doesNotContain("secret-pass-1");
    }

    @Test
    @DisplayName("系统非空时拒绝重新初始化管理员权限")
    void bootstrapRejectsNonEmptySystem() {
        bootstrap.initialize("root-admin", "secret-pass-1");

        assertThatThrownBy(() -> bootstrap.initialize("another-admin", "secret-pass-2"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("SYSTEM_NOT_EMPTY"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_accounts").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("并发引导只成功一次")
    void concurrentBootstrapSucceedsOnce() throws Exception {
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger succeeded = new AtomicInteger();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            String loginName = "admin-" + index;
            tasks.add(() -> {
                start.await();
                try {
                    bootstrap.initialize(loginName, "secret-pass-" + loginName);
                    succeeded.incrementAndGet();
                    return true;
                } catch (ApiException rejected) {
                    return false;
                }
            });
        }
        List<Future<Boolean>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        for (Future<Boolean> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertThat(succeeded).hasValue(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_accounts").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("平台管理员可创建账号、维护角色并授权，全部经过权限与审计")
    void administratorManagesAccountsAndRoles() {
        AccountRef admin = bootstrap.initialize("root-admin", "secret-pass-1");
        SessionRef adminSession = identity.login(new LoginCommand("root-admin", "secret-pass-1"));

        var created = actAs(adminSession, () ->
                accounts.createAccount(new CreateAccountCommand("clerk", "clerk-pass-1", "前台文员")));
        assertThat(created.status()).isEqualTo(AccountStatus.ACTIVE);

        var role = actAs(adminSession, () -> roles.saveRole(new SaveRoleCommand(
                null, "CLERK", "文员", Set.of(PermissionCodes.CUSTOMER_READ, PermissionCodes.ORDER_READ), 0L)));
        assertThat(role.roleCode()).isEqualTo("CLERK");

        actAs(adminSession, () -> {
            roles.assignAccountRoles(new AssignAccountRolesCommand(
                    created.accountBusinessId().value(), Set.of("CLERK"), 1L));
            return null;
        });

        SessionRef clerkSession = identity.login(new LoginCommand("clerk", "clerk-pass-1"));
        CurrentActor clerk = identity.requireCurrentActor(clerkSession.token());
        assertThat(clerk.permissions()).containsExactlyInAnyOrder(
                PermissionCodes.CUSTOMER_READ, PermissionCodes.ORDER_READ);
        assertThat(admin.accountBusinessId()).isNotEqualTo(created.accountBusinessId());
    }

    @Test
    @DisplayName("停用账号后立即失效，既有会话不能再提交命令")
    void disablingAccountInvalidatesImmediately() {
        bootstrap.initialize("root-admin", "secret-pass-1");
        SessionRef adminSession = identity.login(new LoginCommand("root-admin", "secret-pass-1"));
        var clerk = actAs(adminSession, () ->
                accounts.createAccount(new CreateAccountCommand("clerk", "clerk-pass-1", "前台文员")));
        SessionRef clerkSession = identity.login(new LoginCommand("clerk", "clerk-pass-1"));

        actAs(adminSession, () -> accounts.changeAccountStatus(new ChangeAccountStatusCommand(
                clerk.accountBusinessId(), AccountStatus.DISABLED, clerk.rowVersion())));

        assertThatThrownBy(() -> identity.requireCurrentActor(clerkSession.token()))
                .isInstanceOf(ApiException.class)
                .hasMessage("账号已停用");
        assertThatThrownBy(() -> identity.login(new LoginCommand("clerk", "clerk-pass-1")))
                .isInstanceOf(ApiException.class)
                .hasMessage("账号已停用");
    }

    @Test
    @DisplayName("未授权操作者不能管理账号或角色")
    void unauthorizedOperatorCannotAdminister() {
        bootstrap.initialize("root-admin", "secret-pass-1");
        SessionRef adminSession = identity.login(new LoginCommand("root-admin", "secret-pass-1"));
        actAs(adminSession, () -> roles.saveRole(new SaveRoleCommand(
                null, "READER", "只读", Set.of(PermissionCodes.CUSTOMER_READ), 0L)));
        var reader = actAs(adminSession, () ->
                accounts.createAccount(new CreateAccountCommand("reader", "reader-pass-1", "只读员")));
        actAs(adminSession, () -> {
            roles.assignAccountRoles(new AssignAccountRolesCommand(
                    reader.accountBusinessId().value(), Set.of("READER"), 1L));
            return null;
        });
        SessionRef readerSession = identity.login(new LoginCommand("reader", "reader-pass-1"));

        assertThatThrownBy(() -> actAs(readerSession, this::queriesOnlyCreateAccount))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN));

        assertThatThrownBy(() -> actAs(readerSession, () -> roles.saveRole(new SaveRoleCommand(
                null, "HACK", "越权角色", Set.of(PermissionCodes.PLATFORM_ACCOUNT_MANAGE), 0L))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN));

        assertThatThrownBy(() -> actAs(readerSession, () -> accounts.listAccounts()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("角色授权拒绝未定义权限点与重复角色代码，修改使用行版本校验")
    void roleValidationAndVersioning() {
        bootstrap.initialize("root-admin", "secret-pass-1");
        SessionRef adminSession = identity.login(new LoginCommand("root-admin", "secret-pass-1"));

        assertThatThrownBy(() -> actAs(adminSession, () -> roles.saveRole(new SaveRoleCommand(
                null, "BAD", "非法权限", Set.of("not.a.real.permission"), 0L))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("UNKNOWN_PERMISSION"));

        var role = actAs(adminSession, () -> roles.saveRole(new SaveRoleCommand(
                null, "AUDITOR", "审计员", Set.of(PermissionCodes.PLATFORM_AUDIT_READ), 0L)));

        assertThatThrownBy(() -> actAs(adminSession, () -> roles.saveRole(new SaveRoleCommand(
                null, "AUDITOR", "重复代码", Set.of(PermissionCodes.PLATFORM_AUDIT_READ), 0L))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("ROLE_CODE_TAKEN"));

        assertThatThrownBy(() -> actAs(adminSession, () -> roles.saveRole(new SaveRoleCommand(
                role.roleBusinessId().value(), "AUDITOR", "审计员", Set.of(PermissionCodes.PLATFORM_AUDIT_READ),
                role.rowVersion() + 5))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.CONCURRENT_CONFLICT));

        var updatedRef = actAs(adminSession, () -> roles.saveRole(new SaveRoleCommand(
                role.roleBusinessId().value(), "AUDITOR", "高级审计员",
                Set.of(PermissionCodes.PLATFORM_AUDIT_READ, PermissionCodes.FINANCE_READ),
                role.rowVersion())));
        assertThat(updatedRef.rowVersion()).isEqualTo(role.rowVersion() + 1);

        RoleView updated = actAs(adminSession, () -> roles.getRole(role.roleBusinessId().value()));
        assertThat(updated.name()).isEqualTo("高级审计员");
        assertThat(updated.permissionCodes()).contains(PermissionCodes.FINANCE_READ);
    }

    @Test
    @DisplayName("账号与员工资料保持独立：账号创建命令不接受员工标识，表中无绑定列")
    void accountHasNoEmployeeBinding() {
        bootstrap.initialize("root-admin", "secret-pass-1");
        SessionRef adminSession = identity.login(new LoginCommand("root-admin", "secret-pass-1"));
        actAs(adminSession, () ->
                accounts.createAccount(new CreateAccountCommand("clerk", "clerk-pass-1", "前台文员")));

        Set<String> columns = new java.util.HashSet<>(jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_accounts'
                        """).query(String.class).list());

        assertThat(columns).doesNotContain("employee_id", "employee_business_id", "staff_id", "person_id");
        assertThat(List.of(CreateAccountCommand.class.getRecordComponents()).toString())
                .doesNotContain("employee");
    }

    @Test
    @DisplayName("登录失败达到上限后锁定账号，另一管理员可恢复后才能再次登录")
    void failedLoginsLockAccountAndOtherAdministratorCanRecover() {
        bootstrap.initialize("root-admin", "secret-pass-1");
        SessionRef rootSession = identity.login(new LoginCommand("root-admin", "secret-pass-1"));
        var secondAdmin = actAs(rootSession, () ->
                accounts.createAccount(new CreateAccountCommand("ops-admin", "ops-pass-1", "运维管理员")));
        actAs(rootSession, () -> {
            roles.assignAccountRoles(new AssignAccountRolesCommand(
                    secondAdmin.accountBusinessId().value(), Set.of("ADMIN"), 1L));
            return null;
        });

        for (int attempt = 0; attempt < com.yumi.platform.identity.application.IdentityApplicationService.MAX_FAILED_LOGIN_ATTEMPTS; attempt++) {
            assertThatThrownBy(() -> identity.login(new LoginCommand("ops-admin", "wrong-pass")))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("登录名或密码不正确");
        }

        assertThatThrownBy(() -> identity.login(new LoginCommand("ops-admin", "ops-pass-1")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("锁定");

        var locked = jdbc.sql("""
                        SELECT row_version FROM platform_accounts WHERE login_name = 'ops-admin'
                        """).query(Long.class).single();

        actAs(rootSession, () -> accounts.changeAccountStatus(new ChangeAccountStatusCommand(
                secondAdmin.accountBusinessId(), AccountStatus.ACTIVE, locked)));

        assertThat(identity.login(new LoginCommand("ops-admin", "ops-pass-1")).token()).isNotBlank();
    }

    private AccountRef queriesOnlyCreateAccount() {
        return accounts.createAccount(new CreateAccountCommand("bypass", "bypass-pass-1", "绕过"));
    }

    private <T> T actAs(SessionRef session, java.util.function.Supplier<T> body) {
        CurrentActor actor = identity.requireCurrentActor(session.token());
        return CurrentActorHolder.runAs(actor, body);
    }

    /** 当前操作者提供者：读取测试显式设置的请求级操作者。 */
    static final class CurrentActorHolderActorProvider implements com.yumi.platform.api.identity.CurrentActorProvider {

        @Override
        public CurrentActor requireCurrentActor() {
            CurrentActor actor = CurrentActorHolder.get();
            if (actor == null) {
                throw new ApiException(
                        ApiErrorType.FORBIDDEN, "AUTHENTICATION_REQUIRED", "会话无效，请重新登录");
            }
            return actor;
        }
    }

    static final class PlainBcryptHasher implements com.yumi.platform.identity.application.port.PasswordHasher {

        private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);

        @Override
        public String hash(String rawPassword) {
            return encoder.encode(rawPassword);
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return passwordHash != null && encoder.matches(rawPassword, passwordHash);
        }
    }

    static final class Sha256Hasher implements com.yumi.platform.identity.application.port.TokenHasher {

        @Override
        public String hash(String rawToken) {
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                return java.util.HexFormat.of().formatHex(
                        digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
