package com.yumi.workforce.api;

/** 启用小组：至少两名在职成员，原因写入状态记录。 */
public record ActivateTeamCommand(String teamBusinessId, long expectedRowVersion, String reason) {
}
