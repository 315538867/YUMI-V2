package com.yumi.platform.api.authorization;

import java.util.Set;

/**
 * 保存角色命令。
 *
 * @param roleBusinessId 新建时为空
 * @param expectedRowVersion 新建时为 0，修改时为当前行版本
 */
public record SaveRoleCommand(
        String roleBusinessId,
        String roleCode,
        String name,
        Set<String> permissionCodes,
        long expectedRowVersion) {
}
