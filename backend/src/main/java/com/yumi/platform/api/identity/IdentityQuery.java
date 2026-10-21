package com.yumi.platform.api.identity;

import com.yumi.platform.api.value.StableBusinessId;

/** 身份查询：按会话令牌取得当前操作者、读取账号视图。 */
public interface IdentityQuery {

    /**
     * 校验会话并返回当前操作者；会话过期、已吊销或账号非 ACTIVE 时拒绝。
     */
    CurrentActor requireCurrentActor(String sessionToken);

    AccountView getAccount(StableBusinessId accountBusinessId);
}
