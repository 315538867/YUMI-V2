package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import com.yumi.support.RecordingNumbering;
import com.yumi.workforce.api.AssignmentSubjectRef;
import com.yumi.workforce.api.AssignmentSubjectSnapshotBundle;
import com.yumi.workforce.api.AssignmentSubjectSnapshotService;
import com.yumi.workforce.api.AssignmentSubjectType;
import com.yumi.workforce.api.ChangeQualificationCommand;
import com.yumi.workforce.api.CreateTeamCommand;
import com.yumi.workforce.api.EmployeeRef;
import com.yumi.workforce.api.HireEmployeeCommand;
import com.yumi.workforce.api.TeamRef;
import com.yumi.workforce.application.WorkforceCommandService;
import com.yumi.workforce.application.WorkforceSnapshotServiceImpl;
import com.yumi.workforce.infrastructure.JdbcActivityReservationRepository;
import com.yumi.workforce.infrastructure.JdbcWorkforceRepository;
import com.yumi.workforce.infrastructure.JdbcWorkforceSnapshotRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 主体与时间标准快照，唯一键含稳定标准标识与内容版本（WF-04）。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §7.2、§12。真实 MySQL 验证：
 * 两个不同标准的版本 1 不碰撞；历史成员在离职后仍可解析。
 */
class WorkforceSnapshotIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private WorkforceCommandService workforce;

    private AssignmentSubjectSnapshotService snapshots;

    private JdbcWorkforceSnapshotRepository snapshotRepository;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM workforce_activity_reservations").update();
        jdbc.sql("DELETE FROM workforce_activity_reference_guards").update();
        jdbc.sql("DELETE FROM workforce_standard_snapshots").update();
        jdbc.sql("DELETE FROM workforce_team_snapshots").update();
        jdbc.sql("DELETE FROM workforce_employee_snapshots").update();
        jdbc.sql("DELETE FROM workforce_team_member_facts").update();
        jdbc.sql("DELETE FROM workforce_team_members").update();
        jdbc.sql("DELETE FROM workforce_teams").update();
        jdbc.sql("DELETE FROM workforce_qualification_facts").update();
        jdbc.sql("DELETE FROM workforce_qualifications").update();
        jdbc.sql("DELETE FROM workforce_employment_facts").update();
        jdbc.sql("DELETE FROM workforce_employees").update();
        jdbc.sql("UPDATE workforce_team_efficiency_standards SET member_count = 2,"
                + " efficiency_factor = 1.2, content_version = 1, row_version = 1"
                + " WHERE standard_business_id = 'WFE-2'").update();
        jdbc.sql("UPDATE workforce_team_efficiency_standards SET member_count = 3,"
                + " efficiency_factor = 1.3, content_version = 1, row_version = 1"
                + " WHERE standard_business_id = 'WFE-3'").update();

        var audit = new com.yumi.platform.audit.application.AuditService(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        var repository = new JdbcWorkforceRepository(jdbc);
        var authorization = new RecordingAuthorization();
        workforce = new WorkforceCommandService(
                repository,
                new JdbcActivityReservationRepository(jdbc),
                new RecordingNumbering("E-"),
                authorization,
                new RecordingIdentity("ACC-WORKFORCE-TEST"),
                audit,
                Clock.systemUTC(),
                transactionManager);
        snapshotRepository = new JdbcWorkforceSnapshotRepository(jdbc);
        snapshots = new WorkforceSnapshotServiceImpl(
                snapshotRepository,
                repository,
                workforce,
                workforce,
                authorization,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.systemUTC());
    }

    @Test
    @DisplayName("两个不同标准同为版本 1 也不碰撞")
    void sameVersionDifferentStandardsDoNotCollide() {
        var twoMember = snapshots.acquireCurrentTeamEfficiency(2);
        var threeMember = snapshots.acquireCurrentTeamEfficiency(3);

        assertThat(twoMember.standardBusinessId()).isEqualTo("WFE-2");
        assertThat(threeMember.standardBusinessId()).isEqualTo("WFE-3");
        assertThat(twoMember.sourceContentVersion()).isEqualTo(1);
        assertThat(threeMember.sourceContentVersion()).isEqualTo(1);
        assertThat(twoMember.snapshotBusinessId()).isNotEqualTo(threeMember.snapshotBusinessId());
        assertThat(twoMember.fingerprint()).isNotEqualTo(threeMember.fingerprint());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM workforce_standard_snapshots")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("同身份同版本重复取得返回同一快照")
    void sameIdentitySameVersionReusesSnapshot() {
        var first = snapshots.acquireCurrentTeamEfficiency(2);
        var second = snapshots.acquireCurrentTeamEfficiency(2);

        assertThat(second.snapshotBusinessId()).isEqualTo(first.snapshotBusinessId());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM workforce_standard_snapshots")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("历史成员在离职后仍可解析快照")
    void historicalMembersResolveAfterLeaving() {
        EmployeeRef first = hire("张三");
        EmployeeRef second = hire("李四");
        grant("MAKING", first, second);

        TeamRef team = workforce.createTeam(new CreateTeamCommand(
                "二人组", Set.of(first.employeeBusinessId(), second.employeeBusinessId())));
        var bundle = snapshots.acquireCurrentAssignmentSnapshot(
                new AssignmentSubjectRef(AssignmentSubjectType.TEAM, team.teamBusinessId()),
                "MAKING", LocalDate.parse("2026-10-01"));

        assertThat(bundle.subjectView().members()).hasSize(2);
        String firstMemberId = bundle.subjectView().members().getFirst().employeeBusinessId();

        // 任务不再引用后，成员离职
        workforce.leave(new com.yumi.workforce.api.LeaveEmployeeCommand(
                firstMemberId, LocalDate.parse("2026-10-20"), "离职", 1L));

        var resolved = snapshots.resolve(bundle.subjectSnapshot());
        assertThat(resolved.members())
                .extracting(com.yumi.workforce.api.AssignmentSubjectSnapshotView.MemberSnapshot::employeeBusinessId)
                .contains(firstMemberId, second.employeeBusinessId());
    }

    @Test
    @DisplayName("员工快照冻结姓名与工号，资料变化不覆盖旧快照")
    void employeeSnapshotFreezesProfile() {
        EmployeeRef hired = hire("张三");
        grant("MAKING", hired);
        var bundle = snapshots.acquireCurrentAssignmentSnapshot(
                new AssignmentSubjectRef(AssignmentSubjectType.EMPLOYEE, hired.employeeBusinessId()),
                "MAKING", LocalDate.parse("2026-10-01"));

        workforce.updateEmployee(new com.yumi.workforce.api.UpdateEmployeeCommand(
                hired.employeeBusinessId(), "张三（改名）", "13900000000", 1L));

        var resolved = snapshots.resolve(bundle.subjectSnapshot());
        assertThat(resolved.name()).as("历史快照保留原姓名").isEqualTo("张三");

        var refreshed = snapshots.acquireCurrentAssignmentSnapshot(
                new AssignmentSubjectRef(AssignmentSubjectType.EMPLOYEE, hired.employeeBusinessId()),
                "MAKING", LocalDate.parse("2026-10-01"));
        assertThat(refreshed.subjectSnapshot().snapshotBusinessId())
                .isNotEqualTo(bundle.subjectSnapshot().snapshotBusinessId());
        assertThat(refreshed.subjectView().name()).isEqualTo("张三（改名）");
    }

    @Test
    @DisplayName("每日有效时间标准快照可取得并复用")
    void dailyStandardSnapshot() {
        var first = snapshots.acquireCurrentDailyStandard();
        var second = snapshots.acquireCurrentDailyStandard();

        assertThat(first.standardType()).isEqualTo("DAILY_WORK_TIME");
        assertThat(first.snapshotBusinessId()).isEqualTo(second.snapshotBusinessId());
        assertThat(first.fingerprint().value()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("小组快照同时携带效率标准与每日时间标准快照")
    void teamBundleCarriesBothStandards() {
        EmployeeRef first = hire("张三");
        EmployeeRef second = hire("李四");
        grant("MAKING", first, second);
        TeamRef team = workforce.createTeam(new CreateTeamCommand(
                "二人组", Set.of(first.employeeBusinessId(), second.employeeBusinessId())));

        AssignmentSubjectSnapshotBundle bundle = snapshots.acquireCurrentAssignmentSnapshot(
                new AssignmentSubjectRef(AssignmentSubjectType.TEAM, team.teamBusinessId()),
                "MAKING", LocalDate.parse("2026-10-01"));

        assertThat(bundle.efficiencyStandardSnapshot()).isNotNull();
        assertThat(bundle.efficiencyStandardSnapshot().standardBusinessId()).isEqualTo("WFE-2");
        assertThat(bundle.dailyWorkTimeSnapshot().standardType()).isEqualTo("DAILY_WORK_TIME");
        assertThat(bundle.qualifications()).isEmpty();
    }

    private void grant(String workTypeCode, EmployeeRef... employees) {
        List<EmployeeRef> list = List.of(employees);
        for (EmployeeRef employee : list) {
            workforce.grantWorkType(new ChangeQualificationCommand(
                    employee.employeeBusinessId(), workTypeCode, LocalDate.parse("2026-10-01")));
        }
    }

    private EmployeeRef hire(String name) {
        return workforce.hire(new HireEmployeeCommand(name, "13800000000", LocalDate.parse("2026-10-01")));
    }
}
