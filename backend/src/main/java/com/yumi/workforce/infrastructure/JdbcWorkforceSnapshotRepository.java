package com.yumi.workforce.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.workforce.application.port.WorkforceSnapshotRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 员工、小组与标准快照持久化（MySQL）；唯一键按“稳定身份 + 内容版本”防重。 */
@Repository
public class JdbcWorkforceSnapshotRepository implements WorkforceSnapshotRepository {

    private final JdbcClient jdbc;

    public JdbcWorkforceSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SubjectSnapshotRecord> findEmployeeSnapshot(
            String employeeBusinessId, long sourceContentVersion) {
        return jdbc.sql(EMPLOYEE_SELECT + """
                         WHERE employee_business_id = :businessId
                           AND source_content_version = :sourceContentVersion
                        """)
                .param("businessId", employeeBusinessId)
                .param("sourceContentVersion", sourceContentVersion)
                .query((resultSet, rowNumber) -> mapSubject(resultSet, "EMPLOYEE"))
                .optional();
    }

    @Override
    public Optional<SubjectSnapshotRecord> findTeamSnapshot(
            String teamBusinessId, long sourceContentVersion) {
        return jdbc.sql(TEAM_SELECT + """
                         WHERE team_business_id = :businessId
                           AND source_content_version = :sourceContentVersion
                        """)
                .param("businessId", teamBusinessId)
                .param("sourceContentVersion", sourceContentVersion)
                .query((resultSet, rowNumber) -> mapSubject(resultSet, "TEAM"))
                .optional();
    }

    @Override
    public Optional<SubjectSnapshotRecord> findSnapshotByBusinessId(String snapshotBusinessId) {
        Optional<SubjectSnapshotRecord> employee = jdbc.sql(EMPLOYEE_SELECT
                        + " WHERE snapshot_business_id = :snapshotId")
                .param("snapshotId", snapshotBusinessId)
                .query((resultSet, rowNumber) -> mapSubject(resultSet, "EMPLOYEE"))
                .optional();
        if (employee.isPresent()) {
            return employee;
        }
        return jdbc.sql(TEAM_SELECT + " WHERE snapshot_business_id = :snapshotId")
                .param("snapshotId", snapshotBusinessId)
                .query((resultSet, rowNumber) -> mapSubject(resultSet, "TEAM"))
                .optional();
    }

    @Override
    public long maxSubjectSnapshotVersion(String subjectBusinessId) {
        Long employeeMax = jdbc.sql("""
                        SELECT COALESCE(MAX(snapshot_version), 0) FROM workforce_employee_snapshots
                        WHERE employee_business_id = :businessId
                        """)
                .param("businessId", subjectBusinessId)
                .query(Long.class)
                .single();
        Long teamMax = jdbc.sql("""
                        SELECT COALESCE(MAX(snapshot_version), 0) FROM workforce_team_snapshots
                        WHERE team_business_id = :businessId
                        """)
                .param("businessId", subjectBusinessId)
                .query(Long.class)
                .single();
        return Math.max(employeeMax == null ? 0 : employeeMax, teamMax == null ? 0 : teamMax);
    }

    @Override
    public void insertEmployeeSnapshot(SubjectSnapshotRecord record) {
        try {
            jdbc.sql("""
                            INSERT INTO workforce_employee_snapshots (
                                snapshot_business_id, employee_business_id, snapshot_version,
                                source_content_version, payload_json, fingerprint, created_at)
                            VALUES (:snapshotId, :businessId, :snapshotVersion, :sourceContentVersion,
                                    :payload, :fingerprint, :createdAt)
                            """)
                    .param("snapshotId", record.snapshotBusinessId())
                    .param("businessId", record.subjectBusinessId())
                    .param("snapshotVersion", record.snapshotVersion())
                    .param("sourceContentVersion", record.sourceContentVersion())
                    .param("payload", record.payloadJson())
                    .param("fingerprint", record.fingerprint().value())
                    .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new SnapshotConflict(duplicate);
        }
    }

    @Override
    public void insertTeamSnapshot(SubjectSnapshotRecord record) {
        try {
            jdbc.sql("""
                            INSERT INTO workforce_team_snapshots (
                                snapshot_business_id, team_business_id, snapshot_version,
                                source_content_version, payload_json, member_refs_json, fingerprint, created_at)
                            VALUES (:snapshotId, :businessId, :snapshotVersion, :sourceContentVersion,
                                    :payload, :memberRefs, :fingerprint, :createdAt)
                            """)
                    .param("snapshotId", record.snapshotBusinessId())
                    .param("businessId", record.subjectBusinessId())
                    .param("snapshotVersion", record.snapshotVersion())
                    .param("sourceContentVersion", record.sourceContentVersion())
                    .param("payload", record.payloadJson())
                    .param("memberRefs", record.memberRefsJson())
                    .param("fingerprint", record.fingerprint().value())
                    .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new SnapshotConflict(duplicate);
        }
    }

    @Override
    public Optional<StandardSnapshotRecord> findStandardSnapshot(
            String standardType, String standardBusinessId, long sourceContentVersion) {
        return jdbc.sql("""
                        SELECT snapshot_business_id, standard_type, standard_business_id,
                               source_content_version, payload_json, fingerprint, created_at
                        FROM workforce_standard_snapshots
                        WHERE standard_type = :standardType AND standard_business_id = :businessId
                          AND source_content_version = :sourceContentVersion
                        """)
                .param("standardType", standardType)
                .param("businessId", standardBusinessId)
                .param("sourceContentVersion", sourceContentVersion)
                .query((resultSet, rowNumber) -> new StandardSnapshotRecord(
                        resultSet.getString("snapshot_business_id"),
                        resultSet.getString("standard_type"),
                        resultSet.getString("standard_business_id"),
                        resultSet.getLong("source_content_version"),
                        resultSet.getString("payload_json"),
                        ContentFingerprint.of(resultSet.getString("fingerprint")),
                        JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class))))
                .optional();
    }

    @Override
    public void insertStandardSnapshot(StandardSnapshotRecord record) {
        try {
            jdbc.sql("""
                            INSERT INTO workforce_standard_snapshots (
                                snapshot_business_id, standard_type, standard_business_id,
                                snapshot_version, source_content_version, payload_json, fingerprint, created_at)
                            VALUES (:snapshotId, :standardType, :businessId, :snapshotVersion,
                                    :sourceContentVersion, :payload, :fingerprint, :createdAt)
                            """)
                    .param("snapshotId", record.snapshotBusinessId())
                    .param("standardType", record.standardType())
                    .param("businessId", record.standardBusinessId())
                    .param("snapshotVersion", standardSnapshotVersion(record))
                    .param("sourceContentVersion", record.sourceContentVersion())
                    .param("payload", record.payloadJson())
                    .param("fingerprint", record.fingerprint().value())
                    .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new SnapshotConflict(duplicate);
        }
    }

    /** 标准快照版本：同一稳定标准身份内递增。 */
    private long standardSnapshotVersion(StandardSnapshotRecord record) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(snapshot_version), 0) + 1 FROM workforce_standard_snapshots
                        WHERE standard_type = :standardType AND standard_business_id = :businessId
                        """)
                .param("standardType", record.standardType())
                .param("businessId", record.standardBusinessId())
                .query(Long.class)
                .single();
    }

    /** 并发写入同一快照时的显式信号。 */
    public static final class SnapshotConflict extends RuntimeException {

        public SnapshotConflict(Throwable cause) {
            super("相同身份与内容版本的快照已存在", cause);
        }
    }

    private static final String EMPLOYEE_SELECT = """
            SELECT snapshot_business_id, employee_business_id, snapshot_version, source_content_version,
                   payload_json, NULL AS member_refs_json, fingerprint, created_at
            FROM workforce_employee_snapshots
            """;

    private static final String TEAM_SELECT = """
            SELECT snapshot_business_id, team_business_id, snapshot_version, source_content_version,
                   payload_json, member_refs_json, fingerprint, created_at
            FROM workforce_team_snapshots
            """;

    private static SubjectSnapshotRecord mapSubject(ResultSet resultSet, String subjectType)
            throws SQLException {
        return new SubjectSnapshotRecord(
                resultSet.getString("snapshot_business_id"),
                subjectType,
                subjectType.equals("EMPLOYEE")
                        ? resultSet.getString("employee_business_id")
                        : resultSet.getString("team_business_id"),
                resultSet.getLong("snapshot_version"),
                resultSet.getLong("source_content_version"),
                resultSet.getString("payload_json"),
                resultSet.getString("member_refs_json"),
                ContentFingerprint.of(resultSet.getString("fingerprint")),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)));
    }
}
