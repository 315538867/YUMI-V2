package com.yumi.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.AccountRef;
import com.yumi.platform.api.identity.AccountStatus;
import com.yumi.platform.api.identity.ChangeAccountStatusCommand;
import com.yumi.platform.api.identity.CreateAccountCommand;
import com.yumi.platform.api.identity.CurrentActor;
import com.yumi.platform.api.identity.IdentityApplicationService;
import com.yumi.platform.api.identity.IdentityQuery;
import com.yumi.platform.api.identity.LoginCommand;
import com.yumi.platform.api.identity.LogoutCommand;
import com.yumi.platform.api.identity.SessionRef;
import com.yumi.support.InMemoryPlatform;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 身份、会话与资源权限契约（PL-01）。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§6、§10、§12 与
 * `openspec/.../specs/platform-security/spec.md` PL-01。本测试使用内存端口替身，只验证
 * 应用层判定与错误语义；真实数据库持久化、HTTP 绑定和并发在 2.9—2.12 的集成测试验证，
 * 不以本测试宣称真实登录或幂等已通过。
 */
class IdentityAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-10-20T02:00:00Z");

    private InMemoryPlatform platform;

    private IdentityApplicationService identity;

    private IdentityQuery identityQuery;

    private AuthorizationService authorization;

    @BeforeEach
    void setUp() {
        platform = new InMemoryPlatform(Clock.fixed(NOW, ZoneOffset.UTC));
        identity = platform.identityApplicationService();
        identityQuery = platform.identityQuery();
        authorization = platform.authorizationService();
    }

    @Test
    @DisplayName("合法会话可以操作，且当前操作者来自服务端会话而非客户端声明")
    void validSessionCanOperate() {
        AccountRef account = createAdmin();

        SessionRef session = platform.loginAs("admin", "init-pass-1");

        CurrentActor actor = identityQuery.requireCurrentActor(session.token());
        assertThat(actor.accountBusinessId()).isEqualTo(account.accountBusinessId());
        assertThat(actor.loginName()).isEqualTo("admin");
        assertThat(actor.permissions()).contains(PermissionCodes.PLATFORM_ACCOUNT_MANAGE);
    }

    @Test
    @DisplayName("口令错误时拒绝登录，且不泄露账号是否存在")
    void wrongPasswordIsRejectedWithoutEnumeration() {
        createAdmin();

        assertThatThrownBy(() -> identity.login(new LoginCommand("admin", "wrong-pass")))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN))
                .hasMessage("登录名或密码不正确");

        assertThatThrownBy(() -> identity.login(new LoginCommand("nobody", "wrong-pass")))
                .isInstanceOf(ApiException.class)
                .hasMessage("登录名或密码不正确");
    }

    @Test
    @DisplayName("退出后会话立即失效，继续提交命令被拒绝")
    void logoutInvalidatesSession() {
        AccountRef account = createAdmin();
        SessionRef session = platform.loginAs("admin", "init-pass-1");

        identity.logout(new LogoutCommand(session.token()));

        assertThatThrownBy(() -> identityQuery.requireCurrentActor(session.token()))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN));

        // 退出只吊销会话，不改变账号状态
        assertThat(identityQuery.getAccount(account.accountBusinessId()).status())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("会话过期后不能在有效期内继续使用")
    void expiredSessionIsRejected() {
        createAdmin();
        SessionRef session = platform.loginAs("admin", "init-pass-1");

        platform.advanceClock(Duration.ofHours(13));

        assertThatThrownBy(() -> identityQuery.requireCurrentActor(session.token()))
                .isInstanceOf(ApiException.class)
                .hasMessage("会话已过期，请重新登录");
    }

    @Test
    @DisplayName("账号被停用后既有会话立即失效，不需要等待过期")
    void disablingAccountRevokesSessions() {
        AccountRef account = createAdmin();
        SessionRef session = platform.loginAs("admin", "init-pass-1");
        CurrentActor admin = identityQuery.requireCurrentActor(session.token());

        identity.changeAccountStatus(new ChangeAccountStatusCommand(
                account.accountBusinessId(), AccountStatus.DISABLED, account.rowVersion()));

        assertThatThrownBy(() -> identityQuery.requireCurrentActor(session.token()))
                .isInstanceOf(ApiException.class)
                .hasMessage("账号已停用");

        assertThat(platform.sessionsOf(account.accountBusinessId().value()))
                .allMatch(sessionView -> !sessionView.active());
        assertThat(admin.permissions()).isNotEmpty();
    }

    @Test
    @DisplayName("无权限操作者直接调用敏感查询被拒绝，不依赖页面隐藏按钮")
    void operatorWithoutPermissionIsRejected() {
        createAdmin();
        AccountRef reader = createAccountWithRole("reader", "只读员", "READER");
        SessionRef readerSession = platform.loginAs("reader", "reader-pass-1");
        CurrentActor readerActor = identityQuery.requireCurrentActor(readerSession.token());

        assertThatThrownBy(() -> authorization.require(PermissionCodes.PAYROLL_SENSITIVE_READ, ResourceScope.GLOBAL))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN));

        assertThat(readerActor.hasPermission(PermissionCodes.PAYROLL_SENSITIVE_READ)).isFalse();
        assertThat(reader.accountBusinessId()).isEqualTo(readerActor.accountBusinessId());
    }

    @Test
    @DisplayName("权限点变更后下一请求生效，不缓存旧权限")
    void permissionChangeTakesEffectOnNextRequest() {
        createAdmin();
        AccountRef reader = createAccountWithRole("reader", "只读员", "READER");
        SessionRef session = platform.loginAs("reader", "reader-pass-1");

        assertThatThrownBy(() -> authorization.require(PermissionCodes.CUSTOMER_MANAGE, ResourceScope.GLOBAL))
                .isInstanceOf(ApiException.class);

        platform.grantRolePermission("READER", PermissionCodes.CUSTOMER_MANAGE);

        authorization.require(PermissionCodes.CUSTOMER_MANAGE, ResourceScope.GLOBAL);
        assertThat(identityQuery.requireCurrentActor(session.token()).hasPermission(PermissionCodes.CUSTOMER_MANAGE))
                .isTrue();
        assertThat(reader.accountBusinessId().value()).isNotBlank();
    }

    @Test
    @DisplayName("账号与员工资料保持独立：账号模型不含员工绑定字段，创建命令也不接受员工标识")
    void accountIsIndependentFromEmployee() {
        assertThat(AccountRef.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("employeeId", "employeeBusinessId", "staffId", "personId");
        assertThat(CreateAccountCommand.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("employeeId", "employeeBusinessId", "staffId", "personId");
    }

    @Test
    @DisplayName("会话令牌只保存哈希：明文令牌不出现在存储中")
    void sessionTokenIsStoredHashed() {
        createAdmin();
        SessionRef session = platform.loginAs("admin", "init-pass-1");

        assertThat(platform.allStoredTokenHashes()).doesNotContain(session.token());
        assertThat(platform.allStoredTokenHashes())
                .allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("停用账号不能登录，但锁定账号可由管理员恢复")
    void disabledCannotLoginButLockedCanBeRecovered() {
        AccountRef account = createAdmin();
        identity.changeAccountStatus(new ChangeAccountStatusCommand(
                account.accountBusinessId(), AccountStatus.DISABLED, account.rowVersion()));

        assertThatThrownBy(() -> identity.login(new LoginCommand("admin", "init-pass-1")))
                .isInstanceOf(ApiException.class)
                .hasMessage("账号已停用");

        identity.changeAccountStatus(new ChangeAccountStatusCommand(
                account.accountBusinessId(), AccountStatus.ACTIVE, account.rowVersion() + 1));

        assertThat(platform.loginAs("admin", "init-pass-1").token()).isNotBlank();
    }

    @Test
    @DisplayName("使用过期行版本修改账号状态返回并发冲突，而不是静默覆盖")
    void staleRowVersionIsRejected() {
        AccountRef account = createAdmin();

        assertThatThrownBy(() -> identity.changeAccountStatus(new ChangeAccountStatusCommand(
                account.accountBusinessId(), AccountStatus.DISABLED, account.rowVersion() + 5)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.CONCURRENT_CONFLICT));
    }

    private AccountRef createAdmin() {
        AccountRef admin = platform.createInitialAdministrator("admin", "init-pass-1");
        platform.ensureRole("READER", "只读员", java.util.Set.of(
                com.yumi.platform.api.authorization.PermissionCodes.CUSTOMER_READ));
        return admin;
    }

    private AccountRef createAccountWithRole(String loginName, String displayName, String roleCode) {
        return platform.createAccountWithRole(loginName, loginName + "-pass-1", displayName, roleCode);
    }
}
