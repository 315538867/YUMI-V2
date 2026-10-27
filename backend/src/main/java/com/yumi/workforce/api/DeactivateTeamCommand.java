package com.yumi.workforce.api;

/** 停用小组：存在活动引用时拒绝；原因写入状态记录。 */
public record DeactivateTeamCommand(String teamBusinessId, long expectedRowVersion, String reason) {
}
