package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import com.yumi.support.RecordingNumbering;
import com.yumi.workforce.api.ActivityReservationRefs;
import com.yumi.workforce.api.AssignmentSubjectRef;
import com.yumi.workforce.api.AssignmentSubjectType;
import com.yumi.workforce.api.ChangeQualificationCommand;
import com.yumi.workforce.api.ChangeWorkforceActivityCommand;
import com.yumi.workforce.api.CreateTeamCommand;
import com.yumi.workforce.api.EmployeeRef;
import com.yumi.workforce.api.HireEmployeeCommand;
import com.yumi.workforce.api.LeaveEmployeeCommand;
import com.yumi.workforce.api.PreparedActivityReservation;
import com.yumi.workforce.api.ReleaseWorkforceActivityCommand;
import com.yumi.workforce.api.ReservationStatus;
import com.yumi.workforce.api.ReserveWorkforceActivityCommand;
import com.yumi.workforce.api.TeamRef;
import com.yumi.workforce.api.WorkforceActivityReservationParticipant;
import com.yumi.workforce.api.WorkforceActivitySourceRef;
import com.yumi.workforce.application.WorkforceActivityReservationServiceImpl;
import com.yumi.workforce.application.WorkforceCommandService;
import com.yumi.workforce.application.WorkforceSnapshotServiceImpl;
import com.yumi.workforce.infrastructure.JdbcActivityReservationRepository;
import com.yumi.workforce.infrastructure.JdbcWorkforceRepository;
import com.yumi.workforce.infrastructure.JdbcWorkforceSnapshotRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * prepare/commit 预留、调整释放与可用性变更守卫（WF-03）。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §5.4、§8、§10、§12。真实 MySQL 验证：
 * 有活动引用时不能撤销资格或离职；释放后可以变更；预留与释放幂等；调整同事务完成。
 */
class WorkforceReservationIT extends AbstractMySqlIntegrationTest {

    private static final String SOURCE_MODULE = "production";
    private static final String SOURCE_TYPE = "TASK";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private WorkforceCommandService workforce;

    private WorkforceActivityReservationParticipant participant;

    private TransactionTemplate transactionTemplate;

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
        jdbc.sql("UPDATE workforce_team_efficiency_standards SET member_count = 2,"
                + " efficiency_factor = 1.2, content_version = 1, row_version = 1"
                + " WHERE standard_business_id = 'WFE-2'").update();
        jdbc.sql("UPDATE workforce_daily_work_time_standard SET effective_minutes = 480,"
                + " content_version = 1, row_version = 1").update();

        transactionTemplate = new TransactionTemplate(transactionManager);
        var audit = new com.yumi.platform.audit.application.AuditService(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        var authorization = new RecordingAuthorization();
        var repository = new JdbcWorkforceRepository(jdbc);
        var reservationRepository = new JdbcActivityReservationRepository(jdbc);
        workforce = new WorkforceCommandService(
                repository, reservationRepository, new RecordingNumbering("E-"), authorization,
                new RecordingIdentity("ACC-WORKFORCE-TEST"), audit, Clock.systemUTC(), transactionManager);
        var snapshots = new WorkforceSnapshotServiceImpl(
                new JdbcWorkforceSnapshotRepository(jdbc), repository, workforce, workforce,
                authorization, new com.fasterxml.jackson.databind.ObjectMapper(), Clock.systemUTC());
        participant = new WorkforceActivityReservationServiceImpl(
                reservationRepository, snapshots, Clock.systemUTC());
    }

    @Test
    @DisplayName("个人任务预留员工与每日标准；重复提交幂等返回同一预留")
    void employeeReservationIsIdempotent() {
        EmployeeRef employee = hire("张三");
        grant(employee, "MAKING");
        var source = new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, "TASK-1");

        var first = reserve(source, employeeSubject(employee), "MAKING", List.of());
        var second = reserve(source, employeeSubject(employee), "MAKING", List.of());

        assertThat(first.reservations()).isNotEmpty();
        assertThat(first.reservations()).extracting(ActivityReservationRefs.Reservation::reservationBusinessId)
                .containsExactlyElementsOf(second.reservations().stream()
                        .map(ActivityReservationRefs.Reservation::reservationBusinessId)
                        .toList());
        assertThat(countActiveReservations()).isEqualTo(first.reservations().size());
    }

    @Test
    @DisplayName("有活动引用时不能离职或撤销资格；释放后可以")
    void activityReferenceBlocksAvailabilityChange() {
        EmployeeRef employee = hire("张三");
        grant(employee, "MAKING");
        var source = new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, "TASK-2");
        reserve(source, employeeSubject(employee), "MAKING", List.of());

        assertThatThrownBy(() -> workforce.leave(new LeaveEmployeeCommand(
                employee.employeeBusinessId(), LocalDate.parse("2026-10-20"), "离职", 1L)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("WORKFORCE_ACTIVITY_REFERENCED"));

        assertThatThrownBy(() -> workforce.revokeWorkType(new ChangeQualificationCommand(
                employee.employeeBusinessId(), "MAKING", LocalDate.parse("2026-10-20"))))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("WORKFORCE_ACTIVITY_REFERENCED"));

        release(source);

        workforce.leave(new LeaveEmployeeCommand(
                employee.employeeBusinessId(), LocalDate.parse("2026-10-20"), "离职", 1L));
        assertThat(workforce.listEmployees()).singleElement()
                .satisfies(view -> assertThat(view.employmentStatus())
                        .isEqualTo(com.yumi.workforce.api.EmploymentStatus.LEFT));
    }

    @Test
    @DisplayName("小组任务同时预留小组、全部成员与效率标准")
    void teamReservationCoversTeamAndMembers() {
        EmployeeRef first = hire("张三");
        EmployeeRef second = hire("李四");
        grant(first, "MAKING");
        grant(second, "MAKING");
        TeamRef team = workforce.createTeam(new CreateTeamCommand(
                "二人组", Set.of(first.employeeBusinessId(), second.employeeBusinessId())));
        var source = new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, "TASK-3");

        var refs = reserve(
                source, new AssignmentSubjectRef(AssignmentSubjectType.TEAM, team.teamBusinessId()),
                "MAKING", List.of(first.employeeBusinessId(), second.employeeBusinessId()));

        assertThat(refs.reservations())
                .extracting(ActivityReservationRefs.Reservation::scopeType)
                .contains(
                        com.yumi.workforce.api.ReservationScopeType.TEAM,
                        com.yumi.workforce.api.ReservationScopeType.EMPLOYEE,
                        com.yumi.workforce.api.ReservationScopeType.EFFICIENCY_STANDARD,
                        com.yumi.workforce.api.ReservationScopeType.DAILY_WORK_TIME_STANDARD);
        assertThat(refs.reservations())
                .filteredOn(reservation ->
                        reservation.scopeType() == com.yumi.workforce.api.ReservationScopeType.EMPLOYEE)
                .hasSize(2);
    }

    @Test
    @DisplayName("小组成员存在活动引用时不能修改成员")
    void teamMembershipBlockedWhileReferenced() {
        EmployeeRef first = hire("张三");
        EmployeeRef second = hire("李四");
        EmployeeRef third = hire("王五");
        grant(first, "MAKING");
        grant(second, "MAKING");
        grant(third, "MAKING");
        TeamRef team = workforce.createTeam(new CreateTeamCommand(
                "二人组", Set.of(first.employeeBusinessId(), second.employeeBusinessId())));
        var source = new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, "TASK-4");
        reserve(source, new AssignmentSubjectRef(AssignmentSubjectType.TEAM, team.teamBusinessId()),
                "MAKING", List.of());

        assertThatThrownBy(() -> workforce.updateTeam(new com.yumi.workforce.api.UpdateTeamCommand(
                team.teamBusinessId(), "改名组",
                Set.of(first.employeeBusinessId(), third.employeeBusinessId()), 1L)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("WORKFORCE_ACTIVITY_REFERENCED"));

        release(source);

        workforce.updateTeam(new com.yumi.workforce.api.UpdateTeamCommand(
                team.teamBusinessId(), "改名组",
                Set.of(first.employeeBusinessId(), third.employeeBusinessId()), 1L));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM workforce_team_members WHERE team_business_id = :id")
                .param("id", team.teamBusinessId()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("调整预留：释放不再使用的范围并建立新范围，同事务完成")
    void changingReservationReleasesAndCreates() {
        EmployeeRef first = hire("张三");
        EmployeeRef second = hire("李四");
        grant(first, "MAKING");
        grant(first, "BAGGING");
        grant(second, "MAKING");
        TeamRef team = workforce.createTeam(new CreateTeamCommand(
                "二人组", Set.of(first.employeeBusinessId(), second.employeeBusinessId())));
        var source = new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, "TASK-5");
        reserve(source, new AssignmentSubjectRef(AssignmentSubjectType.TEAM, team.teamBusinessId()),
                "MAKING", List.of(first.employeeBusinessId(), second.employeeBusinessId()));

        var changed = transactionTemplate.execute(status -> {
            var prepared = participant.prepareChange(new ChangeWorkforceActivityCommand(
                    source, employeeSubject(first), "BAGGING", LocalDate.parse("2026-10-02"), List.of()));
            return participant.commitChange(prepared);
        });

        assertThat(changed).isNotNull();
        assertThat(countActiveReservations()).isEqualTo(changed.reservations().size());
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM workforce_activity_reservations
                        WHERE status = 'RELEASED'
                        """).query(Long.class).single())
                .as("旧预留保留为历史记录")
                .isPositive();
        assertThat(jdbc.sql("""
                        SELECT COALESCE(SUM(active_reference_count), 0)
                        FROM workforce_activity_reference_guards
                        """).query(Long.class).single())
                .as("守卫计数守恒")
                .isEqualTo(countActiveReservations());
    }

    @Test
    @DisplayName("释放幂等：重复释放不改变状态，也不把计数减成负数")
    void releaseIsIdempotent() {
        EmployeeRef employee = hire("张三");
        grant(employee, "MAKING");
        var source = new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, "TASK-6");
        reserve(source, employeeSubject(employee), "MAKING", List.of());

        release(source);
        release(source);

        assertThat(countActiveReservations()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COALESCE(SUM(active_reference_count), 0)
                        FROM workforce_activity_reference_guards
                        """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM workforce_activity_reservations WHERE status = 'RELEASED'
                        """).query(Long.class).single()).isPositive();
    }

    @Test
    @DisplayName("资格撤销与任务预留竞争只产生合法状态")
    void qualificationRevokeRacesWithReservation() throws Exception {
        EmployeeRef employee = hire("张三");
        grant(employee, "MAKING");

        int threads = 6;
        AtomicInteger reservationsSucceeded = new AtomicInteger();
        AtomicInteger revokesSucceeded = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int index = 0; index < threads; index++) {
            boolean reserveTask = index % 2 == 0;
            int sequence = index;
            tasks.add(() -> {
                start.await();
                try {
                    if (reserveTask) {
                        transactionTemplate.executeWithoutResult(status -> {
                            var source = new WorkforceActivitySourceRef(
                                    SOURCE_MODULE, SOURCE_TYPE, "RACE-" + sequence);
                            var prepared = participant.prepareReserve(new ReserveWorkforceActivityCommand(
                                    source, employeeSubject(employee), "MAKING",
                                    LocalDate.parse("2026-10-01"), List.of()));
                            participant.commitReserve(prepared);
                        });
                        reservationsSucceeded.incrementAndGet();
                    } else {
                        workforce.revokeWorkType(new ChangeQualificationCommand(
                                employee.employeeBusinessId(), "MAKING", LocalDate.parse("2026-10-20")));
                        revokesSucceeded.incrementAndGet();
                    }
                } catch (ApiException expected) {
                    // 守卫拒绝：允许，但不能留下不一致状态
                }
                return null;
            });
        }
        List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(90, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        long activeReservations = countActiveReservations();
        long qualifiedRows = jdbc.sql("""
                        SELECT COUNT(*) FROM workforce_qualifications
                        WHERE employee_business_id = :id AND work_type_code = 'MAKING' AND status = 'ACTIVE'
                        """).param("id", employee.employeeBusinessId()).query(Long.class).single();

        // 只要存在活动预留，资格就必须仍为 ACTIVE；撤销成功则不得有活动预留
        if (activeReservations > 0) {
            assertThat(qualifiedRows).as("活动预留期间资格不得被撤销").isEqualTo(1);
        } else {
            assertThat(qualifiedRows).isZero();
        }
        assertThat(jdbc.sql("""
                        SELECT COALESCE(SUM(active_reference_count), 0)
                        FROM workforce_activity_reference_guards
                        """).query(Long.class).single()).isEqualTo(activeReservations);
    }

    @Test
    @DisplayName("无资格主体不能建立预留")
    void unqualifiedSubjectCannotReserve() {
        EmployeeRef employee = hire("张三");
        grant(employee, "MAKING");
        var source = new WorkforceActivitySourceRef(SOURCE_MODULE, SOURCE_TYPE, "TASK-7");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            var prepared = participant.prepareReserve(new ReserveWorkforceActivityCommand(
                    source, employeeSubject(employee), "SEAM_CUTTING", LocalDate.parse("2026-10-01"),
                    List.of()));
            participant.commitReserve(prepared);
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.BUSINESS_REJECTION));
        assertThat(countActiveReservations()).isZero();
    }

    private ActivityReservationRefs reserve(
            WorkforceActivitySourceRef source, AssignmentSubjectRef subject, String workTypeCode,
            List<String> members) {
        return transactionTemplate.execute(status -> {
            PreparedActivityReservation prepared = participant.prepareReserve(
                    new ReserveWorkforceActivityCommand(
                            source, subject, workTypeCode, LocalDate.parse("2026-10-01"), members));
            return participant.commitReserve(prepared);
        });
    }

    private void release(WorkforceActivitySourceRef source) {
        transactionTemplate.executeWithoutResult(status -> {
            var prepared = participant.prepareRelease(new ReleaseWorkforceActivityCommand(source));
            participant.commitRelease(prepared);
        });
    }

    private long countActiveReservations() {
        return jdbc.sql("SELECT COUNT(*) FROM workforce_activity_reservations WHERE status = 'ACTIVE'")
                .query(Long.class).single();
    }

    private AssignmentSubjectRef employeeSubject(EmployeeRef employee) {
        return new AssignmentSubjectRef(AssignmentSubjectType.EMPLOYEE, employee.employeeBusinessId());
    }

    private void grant(EmployeeRef employee, String workTypeCode) {
        workforce.grantWorkType(new ChangeQualificationCommand(
                employee.employeeBusinessId(), workTypeCode, LocalDate.parse("2026-10-01")));
    }

    private EmployeeRef hire(String name) {
        return workforce.hire(new HireEmployeeCommand(name, "13800000000", LocalDate.parse("2026-10-01")));
    }
}
