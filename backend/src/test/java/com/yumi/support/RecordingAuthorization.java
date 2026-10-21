package com.yumi.support;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限校验测试替身：记录调用点并可注入拒绝。
 *
 * <p>只用于验证业务应用服务确实发起了权限校验；真实认证与授权在平台 HTTP 集成测试中验证。
 */
public final class RecordingAuthorization implements AuthorizationService {

    private final List<String> requiredPermissions = new ArrayList<>();

    private final Set<String> denied = new HashSet<>();

    public void deny(String permissionCode) {
        denied.add(permissionCode);
    }

    public void allowAll() {
        denied.clear();
    }

    public List<String> requiredPermissions() {
        return List.copyOf(requiredPermissions);
    }

    @Override
    public void require(String permissionCode, ResourceScope scope) {
        requiredPermissions.add(permissionCode);
        if (denied.contains(permissionCode)) {
            throw new ApiException(
                    ApiErrorType.FORBIDDEN, "PERMISSION_DENIED", "当前账号没有执行该操作的权限：" + permissionCode);
        }
    }

    @Override
    public boolean isPermitted(String permissionCode, ResourceScope scope) {
        return !denied.contains(permissionCode);
    }
}
