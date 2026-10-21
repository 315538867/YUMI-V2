package com.yumi.workforce.api;

import java.util.Set;

/** 修改小组成员：存在活动引用时拒绝。 */
public record UpdateTeamCommand(
        String teamBusinessId, String name, Set<String> memberEmployeeBusinessIds, long expectedRowVersion) {
}
