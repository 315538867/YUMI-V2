package com.yumi.workforce.api;

/** 停用小组：存在活动引用时拒绝。 */
public record DeactivateTeamCommand(String teamBusinessId, long expectedRowVersion) {
}
