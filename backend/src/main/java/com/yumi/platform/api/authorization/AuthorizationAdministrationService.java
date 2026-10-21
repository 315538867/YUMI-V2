package com.yumi.platform.api.authorization;

/** 角色与账号授权维护；需要 {@code platform.role.manage} 权限并写审计。 */
public interface AuthorizationAdministrationService {

    RoleRef saveRole(SaveRoleCommand command);

    void assignAccountRoles(AssignAccountRolesCommand command);
}
