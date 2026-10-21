package com.yumi.platform.api.authorization;

import java.util.List;

/** 角色与账号授权的只读查询；需要 {@code platform.role.manage} 或 {@code platform.account.manage}。 */
public interface AuthorizationAdministrationQuery {

    List<RoleView> listRoles();

    RoleView getRole(String roleBusinessId);

    List<String> permissionsOfAccount(String accountBusinessId);

    List<String> roleCodesOfAccount(String accountBusinessId);
}
