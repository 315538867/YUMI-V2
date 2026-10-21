package com.yumi.platform.api.authorization;

import java.util.Set;

/** 角色视图：稳定角色代码、名称、授权权限点与行版本。 */
public record RoleView(
        String roleBusinessId,
        String roleCode,
        String name,
        String status,
        Set<String> permissionCodes,
        long rowVersion) {
}
