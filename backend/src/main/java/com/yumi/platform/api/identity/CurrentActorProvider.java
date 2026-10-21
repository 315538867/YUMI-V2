package com.yumi.platform.api.identity;

/**
 * 当前操作者提供者：从已校验会话取得当前账号与权限。
 *
 * <p>业务模块与权限校验都通过本接口获取操作者，不允许从请求参数或页面声明中读取身份。
 * 无有效会话时抛出 {@code FORBIDDEN}。
 */
public interface CurrentActorProvider {

    CurrentActor requireCurrentActor();
}
