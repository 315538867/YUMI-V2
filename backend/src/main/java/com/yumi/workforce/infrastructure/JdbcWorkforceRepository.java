package com.yumi.workforce.infrastructure;

import com.yumi.workforce.api.EmploymentStatus;
import com.yumi.workforce.api.QualificationStatus;
import com.yumi.workforce.api.TeamStatus;
import com.yumi.workforce.application.port.EmployeeRecord;
import com.yumi.workforce.application.port.TeamRecord;
import com.yumi.workforce.application.port.WorkforceRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 人员与小组持久化（MySQL）。 */
@Repository
public class JdbcWorkforceRepository implements WorkforceRepository {

    private final JdbcClient jdbc;

    public JdbcWorkforceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EmployeeRecord> findEmployee(String employeeBusinessId) {
        return jdbc.sql(SELECT_EMPLOYEE + " WHERE employee_business_id = :businessId")
                .param("businessId", employeeBusinessId)
                .query(JdbcWorkforceRepository::mapEmployee)
                .optional();
    }

    @Override
    public Optional<EmployeeRecord> findEmployeeByNo(String employeeNo) {
        return jdbc.sql(SELECT_EMPLOYEE + " WHERE employee_no = :employeeNo")
                .param("employeeNo", employeeNo)
                .query(JdbcWorkforceRepository::mapEmployee)
                .optional();
    }

    @Override
    public List<EmployeeRecord> listEmployees(EmploymentStatus status) {
        String sql = SELECT_EMPLOYEE
                + (status == null ? "" : " WHERE employment_status = :status")
                + " ORDER BY employee_no";
        var spec = jdbc.sql(sql);
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(JdbcWorkforceRepository::mapEmployee).list();
    }

    @Override
    public void insertEmployee(EmployeeRecord record, String operatorId) {
        jdbc.sql("""
                        INSERT INTO workforce_employees (
                            employee_business_id, employee_no, name, phone, employment_status,
                            content_version, row_version, created_at, created_by, updated_at, updated_by)
                        VALUES (:businessId, :employeeNo, :name, :phone, :status, 1, 1,
                                :now, :operatorId, :now, :operatorId)
                        """)
                .param("businessId", record.employeeBusinessId())
                .param("employeeNo", record.employeeNo())
                .param("name", record.name())
                .param("phone", record.phone())
                .param("status", record.employmentStatus().name())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public boolean updateEmployee(
            String employeeBusinessId, String name, String phone, long expectedRowVersion,
            Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE workforce_employees
                        SET name = :name, phone = :phone, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = :now, updated_by = :operatorId
                        WHERE employee_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("name", name)
                .param("phone", phone)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("businessId", employeeBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public boolean updateEmploymentStatus(
            String employeeBusinessId, EmploymentStatus status, LocalDate effectiveDate,
            long expectedRowVersion, Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE workforce_employees
                        SET employment_status = :status, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = :now, updated_by = :operatorId
                        WHERE employee_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("status", status.name())
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("businessId", employeeBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public void insertEmploymentFact(
            String employeeBusinessId, String factType, LocalDate effectiveDate, String reason,
            Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO workforce_employment_facts (
                            fact_business_id, employee_business_id, fact_type, effective_date, reason,
                            is_valid, occurred_at, operator_id)
                        VALUES (:factId, :businessId, :factType, :effectiveDate, :reason, 1, :now, :operatorId)
                        """)
                .param("factId", "WEF-" + UUID.randomUUID())
                .param("businessId", employeeBusinessId)
                .param("factType", factType)
                .param("effectiveDate", Date.valueOf(effectiveDate))
                .param("reason", reason)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public List<QualificationRecord> listQualifications(String employeeBusinessId) {
        return jdbc.sql("""
                        SELECT employee_business_id, work_type_code, status, effective_from, row_version
                        FROM workforce_qualifications
                        WHERE employee_business_id = :businessId ORDER BY work_type_code
                        """)
                .param("businessId", employeeBusinessId)
                .query((resultSet, rowNumber) -> new QualificationRecord(
                        resultSet.getString("employee_business_id"),
                        resultSet.getString("work_type_code"),
                        QualificationStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("effective_from", LocalDate.class),
                        resultSet.getLong("row_version")))
                .list();
    }

    @Override
    public Optional<LocalDate> latestEffectiveDate(String employeeBusinessId) {
        return jdbc.sql("""
                        SELECT MAX(effective_date) FROM workforce_employment_facts
                        WHERE employee_business_id = :businessId
                        """)
                .param("businessId", employeeBusinessId)
                .query(LocalDate.class)
                .optional()
                .filter(java.util.Objects::nonNull);
    }

    @Override
    public boolean hasActiveQualification(String employeeBusinessId, String workTypeCode, LocalDate onDate) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM workforce_qualifications
                            WHERE employee_business_id = :businessId AND work_type_code = :workType
                              AND status = 'ACTIVE' AND effective_from <= :onDate)
                        """)
                .param("businessId", employeeBusinessId)
                .param("workType", workTypeCode)
                .param("onDate", Date.valueOf(onDate))
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void upsertQualification(
            String employeeBusinessId, String workTypeCode, QualificationStatus status,
            LocalDate effectiveFrom, Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO workforce_qualifications (
                            employee_business_id, work_type_code, status, effective_from, row_version)
                        VALUES (:businessId, :workType, :status, :effectiveFrom, 1)
                        ON DUPLICATE KEY UPDATE
                            status = VALUES(status), effective_from = VALUES(effective_from),
                            row_version = row_version + 1
                        """)
                .param("businessId", employeeBusinessId)
                .param("workType", workTypeCode)
                .param("status", status.name())
                .param("effectiveFrom", Date.valueOf(effectiveFrom))
                .update();
    }

    @Override
    public void insertQualificationFact(
            String employeeBusinessId, String workTypeCode, String factType, LocalDate effectiveDate,
            Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO workforce_qualification_facts (
                            fact_business_id, employee_business_id, work_type_code, fact_type,
                            effective_date, is_valid, occurred_at, operator_id)
                        VALUES (:factId, :businessId, :workType, :factType, :effectiveDate, 1, :now, :operatorId)
                        """)
                .param("factId", "WQF-" + UUID.randomUUID())
                .param("businessId", employeeBusinessId)
                .param("workType", workTypeCode)
                .param("factType", factType)
                .param("effectiveDate", Date.valueOf(effectiveDate))
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public boolean workTypeExists(String workTypeCode) {
        Boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM workforce_work_types WHERE work_type_code = :code)")
                .param("code", workTypeCode)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<TeamRecord> findTeam(String teamBusinessId) {
        return jdbc.sql(SELECT_TEAM + " WHERE team_business_id = :businessId")
                .param("businessId", teamBusinessId)
                .query(JdbcWorkforceRepository::mapTeam)
                .optional();
    }

    @Override
    public List<TeamRecord> listTeams() {
        return jdbc.sql(SELECT_TEAM + " ORDER BY team_no").query(JdbcWorkforceRepository::mapTeam).list();
    }

    @Override
    public void insertTeam(TeamRecord record, String operatorId) {
        jdbc.sql("""
                        INSERT INTO workforce_teams (
                            team_business_id, team_no, name, status, content_version, row_version,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (:businessId, :teamNo, :name, :status, 1, 1, :now, :operatorId, :now, :operatorId)
                        """)
                .param("businessId", record.teamBusinessId())
                .param("teamNo", record.teamNo())
                .param("name", record.name())
                .param("status", record.status().name())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public boolean updateTeam(
            String teamBusinessId, String name, long expectedRowVersion, Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE workforce_teams
                        SET name = :name, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = :now, updated_by = :operatorId
                        WHERE team_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("name", name)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("businessId", teamBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public boolean updateTeamStatus(
            String teamBusinessId, TeamStatus status, long expectedRowVersion, Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE workforce_teams
                        SET status = :status, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = :now, updated_by = :operatorId
                        WHERE team_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("status", status.name())
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("businessId", teamBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public List<String> teamMemberIds(String teamBusinessId) {
        return jdbc.sql("""
                        SELECT employee_business_id FROM workforce_team_members
                        WHERE team_business_id = :businessId ORDER BY employee_business_id
                        """)
                .param("businessId", teamBusinessId)
                .query(String.class)
                .list();
    }

    @Override
    public void replaceTeamMembers(
            String teamBusinessId, List<String> employeeBusinessIds, Instant now, String operatorId) {
        jdbc.sql("DELETE FROM workforce_team_members WHERE team_business_id = :businessId")
                .param("businessId", teamBusinessId)
                .update();
        List<String> ordered = employeeBusinessIds.stream().sorted().toList();
        for (String employeeBusinessId : ordered) {
            jdbc.sql("""
                            INSERT INTO workforce_team_members (team_business_id, employee_business_id, joined_at)
                            VALUES (:teamId, :employeeId, :now)
                            """)
                    .param("teamId", teamBusinessId)
                    .param("employeeId", employeeBusinessId)
                    .param("now", JdbcTimes.toUtc(now))
                    .update();
            insertTeamMemberFact(teamBusinessId, employeeBusinessId, "JOIN", now, operatorId);
        }
    }

    @Override
    public void insertTeamMemberFact(
            String teamBusinessId, String employeeBusinessId, String factType, Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO workforce_team_member_facts (
                            fact_business_id, team_business_id, employee_business_id, fact_type,
                            occurred_at, is_valid, operator_id)
                        VALUES (:factId, :teamId, :employeeId, :factType, :now, 1, :operatorId)
                        """)
                .param("factId", "WTF-" + UUID.randomUUID())
                .param("teamId", teamBusinessId)
                .param("employeeId", employeeBusinessId)
                .param("factType", factType)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public List<EfficiencyStandardRecord> listEfficiencyStandards() {
        return jdbc.sql("""
                        SELECT standard_business_id, member_count, efficiency_factor, content_version, row_version
                        FROM workforce_team_efficiency_standards ORDER BY member_count
                        """)
                .query((resultSet, rowNumber) -> new EfficiencyStandardRecord(
                        resultSet.getString("standard_business_id"),
                        resultSet.getInt("member_count"),
                        resultSet.getBigDecimal("efficiency_factor"),
                        resultSet.getLong("content_version"),
                        resultSet.getLong("row_version")))
                .list();
    }

    @Override
    public Optional<EfficiencyStandardRecord> findEfficiencyStandard(String standardBusinessId) {
        return jdbc.sql("""
                        SELECT standard_business_id, member_count, efficiency_factor, content_version, row_version
                        FROM workforce_team_efficiency_standards WHERE standard_business_id = :standardId
                        """)
                .param("standardId", standardBusinessId)
                .query((resultSet, rowNumber) -> new EfficiencyStandardRecord(
                        resultSet.getString("standard_business_id"),
                        resultSet.getInt("member_count"),
                        resultSet.getBigDecimal("efficiency_factor"),
                        resultSet.getLong("content_version"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public Optional<EfficiencyStandardRecord> findEfficiencyStandardByMemberCount(int memberCount) {
        return jdbc.sql("""
                        SELECT standard_business_id, member_count, efficiency_factor, content_version, row_version
                        FROM workforce_team_efficiency_standards WHERE member_count = :memberCount
                        """)
                .param("memberCount", memberCount)
                .query((resultSet, rowNumber) -> new EfficiencyStandardRecord(
                        resultSet.getString("standard_business_id"),
                        resultSet.getInt("member_count"),
                        resultSet.getBigDecimal("efficiency_factor"),
                        resultSet.getLong("content_version"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public boolean updateEfficiencyStandard(
            String standardBusinessId, int memberCount, BigDecimal factor, long expectedRowVersion,
            Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE workforce_team_efficiency_standards
                        SET member_count = :memberCount, efficiency_factor = :factor,
                            content_version = content_version + 1, row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE standard_business_id = :standardId AND row_version = :expectedRowVersion
                        """)
                .param("memberCount", memberCount)
                .param("factor", factor)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("standardId", standardBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public Optional<DailyStandardRecord> findDailyStandard() {
        return jdbc.sql("""
                        SELECT standard_business_id, effective_minutes, content_version, row_version
                        FROM workforce_daily_work_time_standard LIMIT 1
                        """)
                .query((resultSet, rowNumber) -> new DailyStandardRecord(
                        resultSet.getString("standard_business_id"),
                        resultSet.getInt("effective_minutes"),
                        resultSet.getLong("content_version"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public boolean updateDailyStandard(
            String standardBusinessId, int effectiveMinutes, long expectedRowVersion,
            Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE workforce_daily_work_time_standard
                        SET effective_minutes = :minutes, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = :now, updated_by = :operatorId
                        WHERE standard_business_id = :standardId AND row_version = :expectedRowVersion
                        """)
                .param("minutes", effectiveMinutes)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("standardId", standardBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    private static final String SELECT_EMPLOYEE = """
            SELECT id, employee_business_id, employee_no, name, phone, employment_status,
                   content_version, row_version, created_at, updated_at
            FROM workforce_employees
            """;

    private static final String SELECT_TEAM = """
            SELECT id, team_business_id, team_no, name, status, content_version, row_version,
                   created_at, updated_at
            FROM workforce_teams
            """;

    private static EmployeeRecord mapEmployee(ResultSet resultSet, int rowNumber) throws SQLException {
        return new EmployeeRecord(
                resultSet.getLong("id"),
                resultSet.getString("employee_business_id"),
                resultSet.getString("employee_no"),
                resultSet.getString("name"),
                resultSet.getString("phone"),
                EmploymentStatus.valueOf(resultSet.getString("employment_status")),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)));
    }

    private static TeamRecord mapTeam(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TeamRecord(
                resultSet.getLong("id"),
                resultSet.getString("team_business_id"),
                resultSet.getString("team_no"),
                resultSet.getString("name"),
                TeamStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)));
    }
}
