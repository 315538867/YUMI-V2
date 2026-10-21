package com.yumi.platform.identity.application;

import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.identity.AccountAdministrationQuery;
import com.yumi.platform.api.identity.AccountRef;
import com.yumi.platform.api.identity.AccountView;
import com.yumi.platform.api.identity.ChangeAccountStatusCommand;
import com.yumi.platform.api.identity.CreateAccountCommand;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.platform.authorization.application.port.RoleRepository;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号管理应用服务：授权后的账号创建、停用与列表。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9、§10、§12。所有方法先校验
 * {@code platform.account.manage}；命令在与审计同一事务内提交，失败不留下成功审计。
 * 账号与员工资料保持独立，本服务不读写任何人员数据。
 */
public class AccountAdministrationService implements AccountAdministrationQuery {

    private final IdentityApplicationService identity;

    private final RoleRepository roles;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final AuditService audit;

    private final Clock clock;

    public AccountAdministrationService(
            IdentityApplicationService identity,
            RoleRepository roles,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock) {
        this.identity = Objects.requireNonNull(identity, "身份服务不能为空");
        this.roles = Objects.requireNonNull(roles, "角色仓储不能为空");
        this.authorization = Objects.requireNonNull(authorization, "权限服务不能为空");
        this.currentActor = Objects.requireNonNull(currentActor, "当前操作者不能为空");
        this.audit = Objects.requireNonNull(audit, "审计服务不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /** 账号创建：需要 {@code platform.account.manage}。 */
    @Transactional
    public AccountRef createAccount(CreateAccountCommand command) {
        requireAccountManage();
        AccountRef account = identity.createAccount(command);
        recordAudit("CREATE_ACCOUNT", account.accountBusinessId().value(),
                Map.of("loginName", account.loginName()));
        return account;
    }

    /** 账号启停：需要 {@code platform.account.manage}；停用后既有会话立即失效。 */
    @Transactional
    public AccountRef changeAccountStatus(ChangeAccountStatusCommand command) {
        requireAccountManage();
        AccountRef account = identity.changeAccountStatus(command);
        recordAudit("CHANGE_ACCOUNT_STATUS", account.accountBusinessId().value(),
                Map.of("targetStatus", account.status().name()));
        return account;
    }

    @Override
    public List<AccountView> listAccounts() {
        requireAccountManage();
        return identity.allAccounts().stream()
                .map(account -> new AccountView(
                        StableBusinessId.of(account.accountBusinessId()),
                        account.loginName(),
                        account.displayName(),
                        account.status(),
                        account.rowVersion(),
                        roles.findRoleCodesOfAccount(account.accountBusinessId()),
                        account.createdAt()))
                .toList();
    }

    private void requireAccountManage() {
        authorization.require(PermissionCodes.PLATFORM_ACCOUNT_MANAGE, ResourceScope.GLOBAL);
    }

    private void recordAudit(String actionCode, String targetBusinessId, Map<String, String> detail) {
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(),
                currentActor.requireCurrentActor().accountBusinessId().value(),
                "platform.identity",
                actionCode,
                "ACCOUNT",
                targetBusinessId,
                "SUCCESS",
                detail,
                clock.instant()));
    }
}
