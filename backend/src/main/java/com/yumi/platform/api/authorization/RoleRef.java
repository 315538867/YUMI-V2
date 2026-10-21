package com.yumi.platform.api.authorization;

import com.yumi.platform.api.value.StableBusinessId;
import java.util.Set;

/** 角色引用：稳定角色代码与授权权限点。 */
public record RoleRef(StableBusinessId roleBusinessId, String roleCode, Set<String> permissionCodes, long rowVersion) {
}
