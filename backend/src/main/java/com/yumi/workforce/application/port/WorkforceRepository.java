package com.yumi.workforce.application.port;

import com.yumi.workforce.api.EmploymentStatus;
import com.yumi.workforce.api.QualificationStatus;
import com.yumi.workforce.api.TeamStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 员工、资格、小组与标准的持久化端口。 */
public interface WorkforceRepository {

    Optional<EmployeeRecord> findEmployee(String employeeBusinessId);

    Optional<EmployeeRecord> findEmployeeByNo(String employeeNo);

    List<EmployeeRecord> listEmployees(EmploymentStatus status);

    List<EmployeeRecord> searchEmployees(
            String keyword, EmploymentStatus status, String workTypeCode, int offset, int limit);

    void insertEmployee(EmployeeRecord record, String operatorId);

    boolean updateEmployee(
            String employeeBusinessId, String name, String phone, long expectedRowVersion,
            Instant now, String operatorId);

    boolean updateEmploymentStatus(
            String employeeBusinessId, EmploymentStatus status, LocalDate effectiveDate,
            long expectedRowVersion, Instant now, String operatorId);

    void insertEmploymentFact(
            String employeeBusinessId, String factType, LocalDate effectiveDate, String reason,
            Instant now, String operatorId);

    List<QualificationRecord> listQualifications(String employeeBusinessId);

    /** 最近一次就业事实的生效日期；无事实时为空。 */
    java.util.Optional<java.time.LocalDate> latestEffectiveDate(String employeeBusinessId);

    /** 是否存在有效资格（指定日期与工种）。 */
    boolean hasActiveQualification(String employeeBusinessId, String workTypeCode, LocalDate onDate);

    void upsertQualification(
            String employeeBusinessId, String workTypeCode, QualificationStatus status,
            LocalDate effectiveFrom, Instant now, String operatorId);

    void insertQualificationFact(
            String employeeBusinessId, String workTypeCode, String factType, LocalDate effectiveDate,
            Instant now, String operatorId);

    boolean workTypeExists(String workTypeCode);

    Optional<TeamRecord> findTeam(String teamBusinessId);

    List<TeamRecord> listTeams();

    List<TeamRecord> searchTeams(String keyword, TeamStatus status, int offset, int limit);

    void insertTeam(TeamRecord record, String operatorId);

    boolean updateTeam(
            String teamBusinessId, String name, long expectedRowVersion, Instant now, String operatorId);

    boolean updateTeamStatus(
            String teamBusinessId, com.yumi.workforce.api.TeamStatus status, long expectedRowVersion,
            Instant now, String operatorId);

    List<String> teamMemberIds(String teamBusinessId);

    void replaceTeamMembers(
            String teamBusinessId, List<String> employeeBusinessIds, Instant now, String operatorId);

    void insertTeamMemberFact(
            String teamBusinessId, String employeeBusinessId, String factType, Instant now, String operatorId);

    List<EmploymentFactRecord> listEmploymentFacts(String employeeBusinessId);

    List<QualificationFactRecord> listQualificationFacts(String employeeBusinessId);

    List<TeamMemberFactRecord> listTeamMemberFacts(String teamBusinessId);

    List<TeamStatusFactRecord> listTeamStatusFacts(String teamBusinessId);

    void insertTeamStatusFact(
            String teamBusinessId, String factType, TeamStatus status, String reason,
            Instant now, String operatorId);

    List<StandardVersionRecord> listStandardVersions(
            String standardType, String standardBusinessId);

    void insertStandardVersion(StandardVersionRecord record);

    List<EfficiencyStandardRecord> listEfficiencyStandards();

    Optional<EfficiencyStandardRecord> findEfficiencyStandard(String standardBusinessId);

    Optional<EfficiencyStandardRecord> findEfficiencyStandardByMemberCount(int memberCount);

    void insertEfficiencyStandard(EfficiencyStandardRecord record);

    boolean updateEfficiencyStandard(
            String standardBusinessId, int memberCount, java.math.BigDecimal factor,
            long expectedRowVersion, Instant now, String operatorId);

    Optional<DailyStandardRecord> findDailyStandard();

    boolean updateDailyStandard(
            String standardBusinessId, int effectiveMinutes, long expectedRowVersion,
            Instant now, String operatorId);

    /** 就业事实。 */
    record EmploymentFactRecord(
            String factBusinessId, String factType, LocalDate effectiveDate, String reason,
            Instant occurredAt, String operatorId) {
    }

    /** 资格变更事实。 */
    record QualificationFactRecord(
            String factBusinessId, String workTypeCode, String factType,
            LocalDate effectiveDate, Instant occurredAt, String operatorId) {
    }

    /** 小组成员变化事实；员工姓名取当前资料，仅用于展示。 */
    record TeamMemberFactRecord(
            String factBusinessId, String employeeBusinessId, String employeeNo, String employeeName,
            String factType, Instant occurredAt, String operatorId) {
    }

    /** 小组状态事实。 */
    record TeamStatusFactRecord(
            String factBusinessId, String factType, TeamStatus status, String reason,
            Instant occurredAt, String operatorId) {
    }

    /** 标准版本事实：按标准类型区分人数效率与每日有效时间。 */
    record StandardVersionRecord(
            String versionBusinessId, String standardType, String standardBusinessId,
            long contentVersion, Integer memberCount, java.math.BigDecimal efficiencyFactor,
            Integer effectiveMinutes, String reason, Instant occurredAt, String operatorId) {
    }

    /** 工种资格记录。 */
    record QualificationRecord(
            String employeeBusinessId, String workTypeCode, QualificationStatus status,
            LocalDate effectiveFrom, long rowVersion) {
    }

    /** 效率标准记录。 */
    record EfficiencyStandardRecord(
            String standardBusinessId, int memberCount, java.math.BigDecimal efficiencyFactor,
            long contentVersion, long rowVersion) {
    }

    /** 每日有效时间标准记录。 */
    record DailyStandardRecord(
            String standardBusinessId, int effectiveMinutes, long contentVersion, long rowVersion) {
    }
}
