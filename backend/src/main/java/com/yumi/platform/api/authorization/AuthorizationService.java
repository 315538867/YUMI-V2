package com.yumi.platform.api.authorization;

/**
 * 权限校验：业务模块在命令与敏感查询入口调用。
 *
 * <p>页面按钮隐藏不能代替本校验；无权限时抛出 {@code FORBIDDEN}，不返回数据。
 */
public interface AuthorizationService {

    /** 校验当前操作者是否具备权限点；不满足时抛出 {@code ApiException(FORBIDDEN)}。 */
    void require(String permissionCode, ResourceScope scope);

    /** 只读判断，供需要分支展示的场景使用；不得用它代替 {@link #require}。 */
    boolean isPermitted(String permissionCode, ResourceScope scope);
}
