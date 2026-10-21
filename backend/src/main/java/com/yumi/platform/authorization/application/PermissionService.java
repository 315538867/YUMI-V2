package com.yumi.platform.authorization.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActor;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.authorization.application.port.RoleRepository;
import java.util.Objects;

/**
 * 资源权限校验。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§10。每次校验都重新读取当前会话与授权，
 * 不缓存权限；权限点使用稳定代码，页面隐藏按钮不构成保护。资源范围一期只有全局与具体实例两种，
 * 具体实例的“归属”判断仍由业务模块在锁定后完成，本服务只校验权限点。
 */
public class PermissionService implements AuthorizationService {

    private final CurrentActorProvider currentActorProvider;

    private final RoleRepository roles;

    public PermissionService(CurrentActorProvider currentActorProvider, RoleRepository roles) {
        this.currentActorProvider = Objects.requireNonNull(currentActorProvider, "当前操作者提供者不能为空");
        this.roles = Objects.requireNonNull(roles, "角色仓储不能为空");
    }

    @Override
    public void require(String permissionCode, ResourceScope scope) {
        if (permissionCode == null || permissionCode.isBlank()) {
            throw new IllegalArgumentException("权限点不能为空");
        }
        if (!PermissionCodes.isKnown(permissionCode)) {
            throw new IllegalArgumentException("未定义的权限点：" + permissionCode);
        }
        Objects.requireNonNull(scope, "资源范围不能为空");

        CurrentActor actor = currentActorProvider.requireCurrentActor();
        if (!actor.hasPermission(permissionCode)) {
            throw new ApiException(
                    ApiErrorType.FORBIDDEN,
                    "PERMISSION_DENIED",
                    "当前账号没有执行该操作的权限：" + permissionCode);
        }
    }

    @Override
    public boolean isPermitted(String permissionCode, ResourceScope scope) {
        Objects.requireNonNull(scope, "资源范围不能为空");
        CurrentActor actor = currentActorProvider.requireCurrentActor();
        return actor.hasPermission(permissionCode);
    }

    /** 平台内部使用：按账号读取最新权限集合，避免缓存旧授权。 */
    public java.util.Set<String> permissionsOf(String accountBusinessId) {
        return roles.findPermissionsOfAccount(accountBusinessId);
    }
}
