package com.yumi.platform.api.identity;

import com.yumi.platform.api.value.StableBusinessId;

/**
 * 账号引用。
 *
 * <p>平台账号与员工资料是不同概念：本类型不含员工标识、工号或人员绑定字段。
 *
 * @param rowVersion 当前行版本，后续状态变更需携带
 */
public record AccountRef(
        StableBusinessId accountBusinessId, String loginName, AccountStatus status, long rowVersion) {
}
