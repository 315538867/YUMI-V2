package com.yumi.platform.authorization.application;

import com.yumi.platform.api.authorization.AuthorizationAdministrationQuery;
import com.yumi.platform.api.authorization.AuthorizationAdministrationService;

/**
 * 角色管理实现契约别名。
 *
 * <p>HTTP 适配层与测试只依赖 `com.yumi.platform.api.authorization` 的公开接口。
 */
public interface RoleAdministrationServiceContract
        extends AuthorizationAdministrationService, AuthorizationAdministrationQuery {
}
