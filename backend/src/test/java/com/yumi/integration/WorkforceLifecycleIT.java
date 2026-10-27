package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import com.yumi.support.RecordingNumbering;
import com.yumi.workforce.api.ChangeQualificationCommand;
import com.yumi.workforce.api.CreateEfficiencyStandardCommand;
import com.yumi.workforce.api.CreateTeamCommand;
import com.yumi.workforce.api.DeactivateTeamCommand;
import com.yumi.workforce.api.EmployeeRef;
import com.yumi.workforce.api.EmployeeView;
import com.yumi.workforce.api.EmploymentStatus;
import com.yumi.workforce.api.HireEmployeeCommand;
import com.yumi.workforce.api.LeaveEmployeeCommand;
import com.yumi.workforce.api.QualificationStatus;
import com.yumi.workforce.api.RehireEmployeeCommand;
import com.yumi.workforce.api.TeamRef;
import com.yumi.workforce.api.TeamStatus;
import com.yumi.workforce.api.TeamView;
import com.yumi.workforce.api.UpdateEfficiencyStandardCommand;
import com.yumi.workforce.api.UpdateWorkTimeStandardCommand;
import com.yumi.workforce.application.WorkforceCommandService;
import com.yumi.workforce.infrastructure.JdbcActivityReservationRepository;
import com.yumi.workforce.infrastructure.JdbcWorkforceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 就业历史、资格、小组成员、效率及有效时间，不建立账号绑定（WF-01、WF-02）。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §5、§7、§11。真实 MySQL 验证：
 * 返聘保留身份和就业链、成员资格约束生效、无账号绑定列、效率标准与每日时间标准可维护。
 */
class WorkforceLifecycleIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private WorkforceCommandService workforce;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM workforce_activity_reservations").update();
        jdbc.sql("DELETE FROM workforce_activity_reference_guards").update();
        jdbc.sql("DELETE FROM workforce_standard_version_facts").update();
        jdbc.sql("DELETE FROM workforce_standard_snapshots").update();
        jdbc.sql("DELETE FROM workforce_team_snapshots").update();
        jdbc.sql("DELETE FROM workforce_employee_snapshots").update();
        jdbc.sql("DELETE FROM workforce_team_status_facts").update();
        jdbc.sql("DELETE FROM workforce_team_member_facts").update();
        jdbc.sql("DELETE FROM workforce_team_members").update();
        jdbc.sql("DELETE FROM workforce_teams").update();
        jdbc.sql("DELETE FROM workforce_qualification_facts").update();
        jdbc.sql("DELETE FROM workforce_qualifications").update();
        jdbc.sql("DELETE FROM workforce_employment_facts").update();
        jdbc.sql("DELETE FROM workforce_employees").update();
        jdbc.sql("DELETE FROM workforce_team_efficiency_standards WHERE standard_business_id NOT IN ('WFE-2','WFE-3','WFE-4')").update();
        jdbc.sql("UPDATE workforce_team_efficiency_standards SET member_count = 2,"
                + " efficiency_factor = 1.2, content_version = 1, row_version = 1"
                + " WHERE standard_business_id = 'WFE-2'").update();
        jdbc.sql("UPDATE workforce_team_efficiency_standards SET member_count = 3,"
                + " efficiency_factor = 1.3, content_version = 1, row_version = 1"
                + " WHERE standard_business_id = 'WFE-3'").update();
        jdbc.sql("UPDATE workforce_daily_work_time_standard SET effective_minutes = 480,"
                + " content_version = 1, row_version = 1").update();

        var audit = new com.yumi.platform.audit.application.AuditService(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        workforce = new WorkforceCommandService(
                new JdbcWorkforceRepository(jdbc),
                new JdbcActivityReservationRepository(jdbc),
                new RecordingNumbering("E-"),
                new RecordingAuthorization(),
                new RecordingIdentity("ACC-WORKFORCE-TEST"),
                audit,
                Clock.systemUTC(),
                transactionManager);
    }

    @Test
    @DisplayName("员工编号稳定，入职写入就业事实，与平台账号完全独立")
    void hireCreatesStableIdentityWithoutAccountBinding() {
        EmployeeRef hired = hire("张三");

        assertThat(hired.employeeNo()).startsWith("E-");
        assertThat(hired.employmentStatus()).isEqualTo(EmploymentStatus.EMPLOYED);
        assertThat(hired.contentVersion()).isEqualTo(1);

        java.util.Set<String> columns = new java.util.HashSet<>(jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workforce_employees'
                        """).query(String.class).list());
        assertThat(columns)
                .as("一期不提供账号绑定列")
                .doesNotContain("account_business_id", "account_id", "login_name", "platform_account_id");
        assertThat(columns).contains("employee_no", "employment_status", "content_version");

        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM workforce_employment_facts
                        WHERE employee_business_id = :id AND fact_type = 'HIRE'
                        """).param("id", hired.employeeBusinessId()).query(Long.class).single()).isEqualTo(1);

        assertThat(java.util.List.of(HireEmployeeCommand.class.getRecordComponents()))
                .noneMatch(component -> component.getName().toLowerCase().contains("account")
                        || component.getName().toLowerCase().contains("login"));
    }

    @Test
    @DisplayName("返聘保留原身份与完整就业链，历史任务不被改写")
    void rehireKeepsIdentityAndHistory() {
        EmployeeRef hired = hire("张三");
        workforce.leave(new LeaveEmployeeCommand(
                hired.employeeBusinessId(), LocalDate.parse("2026-09-30"), "个人原因", 1L));

        EmployeeRef rehired = workforce.rehire(new RehireEmployeeCommand(
                hired.employeeBusinessId(), LocalDate.parse("2026-10-15"), 2L));

        assertThat(rehired.employeeBusinessId()).isEqualTo(hired.employeeBusinessId());
        assertThat(rehired.employeeNo()).isEqualTo(hired.employeeNo());
        assertThat(rehired.employmentStatus()).isEqualTo(EmploymentStatus.EMPLOYED);

        java.util.List<String> facts = jdbc.sql("""
                        SELECT fact_type FROM workforce_employment_facts
                        WHERE employee_business_id = :id ORDER BY occurred_at, id
                        """).param("id", hired.employeeBusinessId()).query(String.class).list();
        assertThat(facts).containsExactly("HIRE", "LEAVE", "REHIRE");
        var history = workforce.getEmployeeHistory(hired.employeeBusinessId());
        assertThat(history.employment())
                .extracting(com.yumi.workforce.api.EmployeeHistoryView.EmploymentFactView::factType)
                .containsExactly("REHIRE", "HIRE", "LEAVE");
        assertThat(hired.employeeNo())
                .as("员工编号不可复用")
                .isEqualTo(rehired.employeeNo());
    }

    @Test
    @DisplayName("离职员工不能加入小组，也不能被授予资格")
    void leftEmployeeCannotJoinOrBeGranted() {
        EmployeeRef first = hire("张三");
        EmployeeRef second = hire("李四");
        workforce.leave(new LeaveEmployeeCommand(
                second.employeeBusinessId(), LocalDate.parse("2026-09-30"), "离职", 1L));

        assertThatThrownBy(() -> workforce.createTeam(new CreateTeamCommand(
                "一组", Set.of(first.employeeBusinessId(), second.employeeBusinessId()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("EMPLOYEE_LEFT"));

        assertThatThrownBy(() -> workforce.grantWorkType(new ChangeQualificationCommand(
                second.employeeBusinessId(), "MAKING", LocalDate.parse("2026-10-01"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("EMPLOYEE_LEFT"));
    }

    @Test
    @DisplayName("小组至少两名在职成员；成员按稳定标识升序返回")
    void teamRequiresTwoActiveMembers() {
        EmployeeRef first = hire("张三");
        assertThatThrownBy(() -> workforce.createTeam(new CreateTeamCommand(
                "单人组", Set.of(first.employeeBusinessId()))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("WORKFORCE_TEAM_INVALID"));

        EmployeeRef second = hire("李四");
        EmployeeRef third = hire("王五");
        TeamRef team = workforce.createTeam(new CreateTeamCommand(
                "三人组", Set.of(third.employeeBusinessId(), first.employeeBusinessId(),
                        second.employeeBusinessId())));

        TeamView view = workforce.getTeam(team.teamBusinessId());
        assertThat(view.members()).hasSize(3);
        assertThat(view.members()).extracting(TeamView.TeamMemberView::employeeBusinessId).isSorted();
        assertThat(view.efficiencyFactor()).isEqualByComparingTo("1.3");
        assertThat(view.members()).allMatch(member -> member.employmentStatus() == EmploymentStatus.EMPLOYED);
        assertThat(workforce.searchTeams("三人", TeamStatus.ACTIVE, 1, 10))
                .singleElement()
                .satisfies(teamView -> assertThat(teamView.teamBusinessId())
                        .isEqualTo(team.teamBusinessId()));
        assertThat(workforce.searchEmployees("李四", EmploymentStatus.EMPLOYED, "", 1, 10))
                .singleElement()
                .satisfies(employeeView -> assertThat(employeeView.name()).isEqualTo("李四"));
        assertThat(workforce.searchEmployees("", EmploymentStatus.EMPLOYED, "", 1, 2))
                .hasSize(2);
    }

    @Test
    @DisplayName("小组不保存组长、承接范围或贡献比例")
    void teamHasNoLeaderOrContribution() {
        java.util.Set<String> columns = new java.util.HashSet<>(jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME IN ('workforce_teams', 'workforce_team_members')
                        """).query(String.class).list());
        assertThat(columns).doesNotContain(
                "leader_employee_business_id", "leader_id", "scope", "contribution_ratio", "position");
    }

    @Test
    @DisplayName("员工与小组详情按标识读取，不存在时返回明确错误")
    void detailLookupUsesBusinessId() {
        EmployeeRef hired = hire("详情员工");
        assertThat(workforce.getEmployee(hired.employeeBusinessId()).name()).isEqualTo("详情员工");

        assertThatThrownBy(() -> workforce.getEmployee("EMP-NOT-EXIST"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("EMPLOYEE_NOT_FOUND"));
        assertThatThrownBy(() -> workforce.getTeam("TEAM-NOT-EXIST"))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("WORKFORCE_TEAM_NOT_FOUND"));
    }

    @Test
    @DisplayName("资格授予与撤销写入事实，可追溯")
    void qualificationChangesAreTraced() {
        EmployeeRef hired = hire("张三");

        workforce.grantWorkType(new ChangeQualificationCommand(
                hired.employeeBusinessId(), "MAKING", LocalDate.parse("2026-10-01")));
        workforce.revokeWorkType(new ChangeQualificationCommand(
                hired.employeeBusinessId(), "MAKING", LocalDate.parse("2026-10-10")));

        EmployeeView view = workforce.getEmployee(hired.employeeBusinessId());
        assertThat(view.qualifications()).singleElement().satisfies(qualification -> {
            assertThat(qualification.workTypeCode()).isEqualTo("MAKING");
            assertThat(qualification.status()).isEqualTo(QualificationStatus.REVOKED);
        });

        java.util.List<String> facts = jdbc.sql("""
                        SELECT fact_type FROM workforce_qualification_facts
                        WHERE employee_business_id = :id ORDER BY occurred_at, id
                        """).param("id", hired.employeeBusinessId()).query(String.class).list();
        assertThat(facts).containsExactly("GRANT", "REVOKE");
        var history = workforce.getEmployeeHistory(hired.employeeBusinessId());
        assertThat(history.qualifications())
                .extracting(com.yumi.workforce.api.EmployeeHistoryView.QualificationFactView::factType)
                .containsExactly("REVOKE", "GRANT");
    }

    @Test
    @DisplayName("未知工种被拒绝")
    void unknownWorkTypeRejected() {
        EmployeeRef hired = hire("张三");
        assertThatThrownBy(() -> workforce.grantWorkType(new ChangeQualificationCommand(
                hired.employeeBusinessId(), "UNKNOWN", LocalDate.parse("2026-10-01"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("UNKNOWN_WORK_TYPE"));
    }

    @Test
    @DisplayName("效率因子必须大于 1，人数至少为 2；修改递增内容版本")
    void efficiencyStandardValidation() {
        assertThatThrownBy(() -> workforce.updateEfficiencyStandard(
                new UpdateEfficiencyStandardCommand("WFE-2", 2, new BigDecimal("0.9"), 1L, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("大于 1");

        workforce.updateEfficiencyStandard(
                new UpdateEfficiencyStandardCommand("WFE-2", 2, new BigDecimal("1.25"), 1L, "调整说明"));

        var standards = workforce.getStandards();
        assertThat(standards.efficiencyStandards())
                .filteredOn(standard -> standard.standardBusinessId().equals("WFE-2"))
                .singleElement()
                .satisfies(standard -> {
                    assertThat(standard.efficiencyFactor()).isEqualByComparingTo("1.25");
                    assertThat(standard.contentVersion()).isEqualTo(2);
                });
        var versions = workforce.getStandardHistory("EFFICIENCY", "WFE-2");
        assertThat(versions.versions()).first().satisfies(version -> {
            assertThat(version.contentVersion()).isEqualTo(2);
            assertThat(version.efficiencyFactor()).isEqualByComparingTo("1.25");
            assertThat(version.reason()).isEqualTo("调整说明");
        });
    }

    @Test
    @DisplayName("新增人数效率标准写入首版，重复人数被拒绝")
    void createEfficiencyStandardIsVersioned() {
        workforce.createEfficiencyStandard(
                new CreateEfficiencyStandardCommand(5, new BigDecimal("1.5"), "新增五人大组标准"));

        var standard = workforce.getStandards().efficiencyStandards().stream()
                .filter(row -> row.memberCount() == 5)
                .findFirst()
                .orElseThrow();
        assertThat(standard.efficiencyFactor()).isEqualByComparingTo("1.5");
        var version = workforce.getStandardHistory("EFFICIENCY", standard.standardBusinessId())
                .versions().getFirst();
        assertThat(version.contentVersion()).isEqualTo(1);
        assertThat(version.reason()).isEqualTo("新增五人大组标准");

        assertThatThrownBy(() -> workforce.createEfficiencyStandard(
                new CreateEfficiencyStandardCommand(5, new BigDecimal("1.6"), null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已有当前效率标准");
    }

    @Test
    @DisplayName("每日有效时间标准可维护，内容版本递增，历史不变")
    void dailyWorkTimeStandardIsVersioned() {
        workforce.updateWorkTimeStandard(new UpdateWorkTimeStandardCommand(420, 1L, "调整说明"));

        var standards = workforce.getStandards();
        assertThat(standards.effectiveMinutes()).isEqualTo(420);
        assertThat(standards.dailyContentVersion()).isEqualTo(2);

        assertThatThrownBy(() -> workforce.updateWorkTimeStandard(
                new UpdateWorkTimeStandardCommand(0, 2L, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("必须大于 0");

        var versions = workforce.getStandardHistory("DAILY_WORK_TIME", standards.dailyStandardBusinessId());
        assertThat(versions.versions()).first().satisfies(version -> {
            assertThat(version.contentVersion()).isEqualTo(2);
            assertThat(version.effectiveMinutes()).isEqualTo(420);
            assertThat(version.reason()).isEqualTo("调整说明");
        });
    }

    @Test
    @DisplayName("并发修改使用行版本拒绝，不静默覆盖")
    void staleRowVersionRejected() {
        EmployeeRef hired = hire("张三");
        workforce.updateEmployee(new com.yumi.workforce.api.UpdateEmployeeCommand(
                hired.employeeBusinessId(), "张三（改）", "13800000000", 1L));

        assertThatThrownBy(() -> workforce.updateEmployee(new com.yumi.workforce.api.UpdateEmployeeCommand(
                hired.employeeBusinessId(), "张三（再改）", "13800000000", 1L)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.CONCURRENT_CONFLICT));
    }

    @Test
    @DisplayName("停用小组需要确认无活动引用")
    void deactivatingTeamChecksActivityReference() {
        EmployeeRef first = hire("张三");
        EmployeeRef second = hire("李四");
        TeamRef team = workforce.createTeam(new CreateTeamCommand(
                "二人组", Set.of(first.employeeBusinessId(), second.employeeBusinessId())));

        workforce.deactivateTeam(new DeactivateTeamCommand(team.teamBusinessId(), 1L, "停用说明"));

        TeamView view = workforce.listTeams().stream()
                .filter(candidate -> candidate.teamBusinessId().equals(team.teamBusinessId()))
                .findFirst()
                .orElseThrow();
        assertThat(view.status()).isEqualTo(TeamStatus.INACTIVE);

        var history = workforce.getTeamHistory(team.teamBusinessId());
        assertThat(history.statuses())
                .extracting(com.yumi.workforce.api.TeamHistoryView.StatusFactView::factType)
                .containsExactly("DEACTIVATE", "CREATE");
        assertThat(history.statuses()).first()
                .satisfies(status -> assertThat(status.reason()).isEqualTo("停用说明"));
        assertThat(history.members())
                .extracting(com.yumi.workforce.api.TeamHistoryView.MemberFactView::factType)
                .containsOnly("JOIN");
    }

    private EmployeeRef hire(String name) {
        return workforce.hire(new HireEmployeeCommand(name, "13800000000", LocalDate.parse("2026-10-01")));
    }
}
