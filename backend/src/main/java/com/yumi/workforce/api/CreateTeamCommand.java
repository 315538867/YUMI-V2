package com.yumi.workforce.api;

import java.util.Set;

/** 创建小组：至少两名当前在职员工。 */
public record CreateTeamCommand(String name, Set<String> memberEmployeeBusinessIds) {
}
