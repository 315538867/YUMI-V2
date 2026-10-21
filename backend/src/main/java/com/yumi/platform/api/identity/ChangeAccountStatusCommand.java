package com.yumi.platform.api.identity;

import com.yumi.platform.api.value.StableBusinessId;

/** 变更账号状态命令：停用、锁定或恢复。 */
public record ChangeAccountStatusCommand(
        StableBusinessId accountBusinessId, AccountStatus targetStatus, long expectedRowVersion) {
}
