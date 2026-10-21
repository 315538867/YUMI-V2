package com.yumi.platform.authorization.application;

import com.yumi.platform.api.authorization.AssignAccountRolesCommand;
import com.yumi.platform.api.authorization.AuthorizationAdministrationQuery;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.authorization.RoleRef;
import com.yumi.platform.api.authorization.RoleView;
import com.yumi.platform.api.authorization.SaveRoleCommand;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.authorization.application.port.RoleRecord;
import com.yumi.platform.authorization.application.port.RoleRepository;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 角色维护与账号授权。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§10、§12：
 * <ul>
 *   <li>需要 {@code platform.role.manage}；权限点必须是已登记的一期稳定代码，不能凭实现便利新增；</li>
 *   <li>修改角色使用行版本校验，冲突返回并发错误；</li>
 *   <li>账号角色以完整集合覆盖，避免增量语义歧义；</li>
 *   <li>不建立员工与账号的绑定。</li>
 * </ul>
 */
public class RoleAdministrationService implements RoleAdministrationServiceContract {

    private static final Pattern ROLE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final RoleRepository roles;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final Clock clock;

    private final AuditService audit;

    public RoleAdministrationService(
            RoleRepository roles,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock,
            AuditService audit) {
        this.roles = Objects.requireNonNull(roles, "角色仓储不能为空");
        this.authorization = Objects.requireNonNull(authorization, "权限服务不能为空");
        this.currentActor = Objects.requireNonNull(currentActor, "当前操作者不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        this.audit = Objects.requireNonNull(audit, "审计服务不能为空");
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public RoleRef saveRole(SaveRoleCommand command) {
        requireRoleManage();
        Objects.requireNonNull(command, "保存角色命令不能为空");

        String roleCode = command.roleCode() == null ? "" : command.roleCode().strip();
        if (!ROLE_CODE.matcher(roleCode).matches()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION,
                    "INVALID_ROLE_CODE",
                    "角色代码必须为 2—64 位大写字母、数字或下划线，且以字母开头");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_ROLE_NAME", "角色名称不能为空");
        }
        Set<String> permissionCodes = validatePermissions(command.permissionCodes());
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();

        if (command.roleBusinessId() == null || command.roleBusinessId().isBlank()) {
            if (roles.findByRoleCode(roleCode).isPresent()) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "ROLE_CODE_TAKEN", "角色代码已存在：" + roleCode);
            }
            String roleBusinessId = "ROLE-" + UUID.randomUUID();
            roles.insert(new RoleRecord(
                    0L, roleBusinessId, roleCode, command.name().strip(), "ACTIVE", 0L,
                    permissionCodes, now));
            recordAudit("CREATE_ROLE", roleBusinessId, Map.of("roleCode", roleCode));
            return new RoleRef(
                    com.yumi.platform.api.value.StableBusinessId.of(roleBusinessId),
                    roleCode, permissionCodes, 1L);
        }

        RoleRecord existing = roles.findByBusinessId(command.roleBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ROLE_NOT_FOUND", "角色不存在"));
        if (!existing.roleCode().equals(roleCode)) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION,
                    "ROLE_CODE_IMMUTABLE",
                    "角色代码创建后不可修改");
        }
        if (!roles.updateRole(
                existing.roleBusinessId(), command.name().strip(), permissionCodes,
                command.expectedRowVersion(), now, operatorId)) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT,
                    "ROW_VERSION_CONFLICT",
                    "角色已被其他操作修改，请刷新后重试");
        }
        recordAudit("UPDATE_ROLE", existing.roleBusinessId(), Map.of("roleCode", roleCode));
        RoleRecord updated = roles.findByBusinessId(existing.roleBusinessId()).orElseThrow();
        return new RoleRef(
                com.yumi.platform.api.value.StableBusinessId.of(updated.roleBusinessId()),
                updated.roleCode(), updated.permissionCodes(), updated.rowVersion());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void assignAccountRoles(AssignAccountRolesCommand command) {
        requireRoleManage();
        Objects.requireNonNull(command, "授权命令不能为空");
        if (command.accountBusinessId() == null || command.accountBusinessId().isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_ACCOUNT", "账号标识不能为空");
        }
        Set<String> roleCodes = command.roleCodes() == null ? Set.of() : Set.copyOf(command.roleCodes());
        for (String roleCode : roleCodes) {
            roles.findByRoleCode(roleCode)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "ROLE_NOT_FOUND", "角色不存在：" + roleCode));
        }
        roles.replaceAccountRoles(
                command.accountBusinessId(), roleCodes, clock.instant(),
                currentActor.requireCurrentActor().accountBusinessId().value());
        recordAudit("ASSIGN_ACCOUNT_ROLES", command.accountBusinessId(),
                Map.of("roleCodes", String.join(",", roleCodes.stream().sorted().toList())));
    }

    @Override
    public List<RoleView> listRoles() {
        requireRoleManage();
        return roles.findAll().stream()
                .map(RoleAdministrationService::toView)
                .toList();
    }

    @Override
    public RoleView getRole(String roleBusinessId) {
        requireRoleManage();
        return roles.findByBusinessId(roleBusinessId)
                .map(RoleAdministrationService::toView)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ROLE_NOT_FOUND", "角色不存在"));
    }

    @Override
    public List<String> permissionsOfAccount(String accountBusinessId) {
        requireRoleManage();
        return List.copyOf(roles.findPermissionsOfAccount(accountBusinessId));
    }

    @Override
    public List<String> roleCodesOfAccount(String accountBusinessId) {
        requireRoleManage();
        return List.copyOf(roles.findRoleCodesOfAccount(accountBusinessId));
    }

    private Set<String> validatePermissions(Set<String> permissionCodes) {
        Set<String> requested = permissionCodes == null ? Set.of() : permissionCodes;
        Set<String> validated = new LinkedHashSet<>();
        for (String permissionCode : requested) {
            if (!PermissionCodes.isKnown(permissionCode)) {
                throw new ApiException(
                        ApiErrorType.VALIDATION,
                        "UNKNOWN_PERMISSION",
                        "未定义的权限点：" + permissionCode);
            }
            validated.add(permissionCode);
        }
        return Set.copyOf(validated);
    }

    private void recordAudit(String actionCode, String targetBusinessId, Map<String, String> detail) {
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(),
                currentActor.requireCurrentActor().accountBusinessId().value(),
                "platform.authorization",
                actionCode,
                "ROLE",
                targetBusinessId,
                "SUCCESS",
                detail,
                clock.instant()));
    }

    private void requireRoleManage() {
        authorization.require(PermissionCodes.PLATFORM_ROLE_MANAGE, ResourceScope.GLOBAL);
    }

    private static RoleView toView(RoleRecord record) {
        return new RoleView(
                record.roleBusinessId(), record.roleCode(), record.name(), record.status(),
                record.permissionCodes(), record.rowVersion());
    }
}
