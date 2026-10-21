package com.yumi.platform.api.identity;

import java.util.List;

/** 账号列表查询；需要 {@code platform.account.manage}，不返回口令哈希与令牌。 */
public interface AccountAdministrationQuery {

    List<AccountView> listAccounts();
}
