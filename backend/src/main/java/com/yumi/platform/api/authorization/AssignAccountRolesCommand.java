package com.yumi.platform.api.authorization;

import java.util.Set;

/** 账号角色分配命令：以完整集合覆盖，避免增量操作产生歧义。 */
public record AssignAccountRolesCommand(
        String accountBusinessId, Set<String> roleCodes, long expectedAccountRowVersion) {
}
