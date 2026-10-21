package com.yumi.platform.api.identity;

import com.yumi.platform.api.value.StableBusinessId;
import java.util.Set;

/**
 * 当前操作者：来自已校验会话的账号身份与权限集合。
 *
 * <p>权限集合在每次请求时从角色授权读取，不信任客户端传入的权限声明。
 */
public record CurrentActor(
        StableBusinessId accountBusinessId,
        String loginName,
        StableBusinessId sessionBusinessId,
        Set<String> permissions) {

    public CurrentActor {
        permissions = Set.copyOf(permissions);
    }

    public boolean hasPermission(String permissionCode) {
        return permissions.contains(permissionCode);
    }
}
