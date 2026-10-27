package com.yumi.workforce.api;

import java.util.List;

/**
 * 人员与小组命令。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §5。员工与平台账号完全分开，一期没有绑定关系。
 */
public interface WorkforceApplicationService {

    EmployeeRef hire(HireEmployeeCommand command);

    EmployeeRef updateEmployee(UpdateEmployeeCommand command);

    EmployeeRef leave(LeaveEmployeeCommand command);

    EmployeeRef rehire(RehireEmployeeCommand command);

    void grantWorkType(ChangeQualificationCommand command);

    void revokeWorkType(ChangeQualificationCommand command);

    TeamRef createTeam(CreateTeamCommand command);

    TeamRef updateTeam(UpdateTeamCommand command);

    void deactivateTeam(DeactivateTeamCommand command);

    void updateEfficiencyStandard(UpdateEfficiencyStandardCommand command);

    void updateWorkTimeStandard(UpdateWorkTimeStandardCommand command);

    List<EmployeeView> listEmployees();

    List<EmployeeView> searchEmployees(
            String keyword, com.yumi.workforce.api.EmploymentStatus status, String workTypeCode,
            int page, int size);

    EmployeeView getEmployee(String employeeBusinessId);

    List<TeamView> listTeams();

    List<TeamView> searchTeams(
            String keyword, com.yumi.workforce.api.TeamStatus status, int page, int size);

    TeamView getTeam(String teamBusinessId);
}
