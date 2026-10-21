package com.yumi.workforce.api;

import java.util.List;

/**
 * 小组当前定义。
 *
 * <p>不保存组长、承接范围、组内岗位或成员贡献比例。
 */
public record TeamView(
        String teamBusinessId,
        String teamNo,
        String name,
        TeamStatus status,
        long contentVersion,
        long rowVersion,
        List<TeamMemberView> members,
        java.math.BigDecimal efficiencyFactor,
        long efficiencyStandardContentVersion) {

    public TeamView {
        members = List.copyOf(members);
    }

    /** 成员视图：按员工稳定业务标识升序。 */
    public record TeamMemberView(
            String employeeBusinessId, String employeeNo, String name, EmploymentStatus employmentStatus) {
    }
}
