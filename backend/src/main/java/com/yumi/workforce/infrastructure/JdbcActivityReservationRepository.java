package com.yumi.workforce.infrastructure;

import com.yumi.workforce.api.ReservationScopeType;
import com.yumi.workforce.api.ReservationStatus;
import com.yumi.workforce.api.WorkforceActivitySourceRef;
import com.yumi.workforce.application.port.ActivityReservationRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 活动守卫与预留持久化（MySQL）。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §7.3、§10：
 * <ul>
 *   <li>守卫统一按 {@code guard_type + guarded_business_id} 排序加锁；</li>
 *   <li>首次使用也须幂等建守卫行后加锁，不锁不存在的投影充当保护；</li>
 *   <li>守卫计数非负；释放只改状态与计数，不删除历史记录。</li>
 * </ul>
 */
@Repository
public class JdbcActivityReservationRepository implements ActivityReservationRepository {

    private final JdbcClient jdbc;

    public JdbcActivityReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public GuardRecord lockGuard(ReservationScopeType guardType, String guardedBusinessId) {
        // 幂等建行：不存在时插入计数 0 的守卫
        jdbc.sql("""
                        INSERT INTO workforce_activity_reference_guards (
                            guard_type, guarded_business_id, active_reference_count, row_version)
                        VALUES (:guardType, :guardedId, 0, 1)
                        ON DUPLICATE KEY UPDATE row_version = row_version
                        """)
                .param("guardType", guardType.name())
                .param("guardedId", guardedBusinessId)
                .update();

        return jdbc.sql("""
                        SELECT guard_type, guarded_business_id, active_reference_count, row_version
                        FROM workforce_activity_reference_guards
                        WHERE guard_type = :guardType AND guarded_business_id = :guardedId
                        FOR UPDATE
                        """)
                .param("guardType", guardType.name())
                .param("guardedId", guardedBusinessId)
                .query((resultSet, rowNumber) -> new GuardRecord(
                        ReservationScopeType.valueOf(resultSet.getString("guard_type")),
                        resultSet.getString("guarded_business_id"),
                        resultSet.getInt("active_reference_count"),
                        resultSet.getLong("row_version")))
                .single();
    }

    @Override
    public int activeReferenceCount(ReservationScopeType guardType, String guardedBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(
                            (SELECT active_reference_count FROM workforce_activity_reference_guards
                             WHERE guard_type = :guardType AND guarded_business_id = :guardedId), 0)
                        """)
                .param("guardType", guardType.name())
                .param("guardedId", guardedBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void incrementGuard(ReservationScopeType guardType, String guardedBusinessId) {
        jdbc.sql("""
                        UPDATE workforce_activity_reference_guards
                        SET active_reference_count = active_reference_count + 1, row_version = row_version + 1
                        WHERE guard_type = :guardType AND guarded_business_id = :guardedId
                        """)
                .param("guardType", guardType.name())
                .param("guardedId", guardedBusinessId)
                .update();
    }

    @Override
    public void decrementGuard(ReservationScopeType guardType, String guardedBusinessId) {
        int updated = jdbc.sql("""
                        UPDATE workforce_activity_reference_guards
                        SET active_reference_count = active_reference_count - 1, row_version = row_version + 1
                        WHERE guard_type = :guardType AND guarded_business_id = :guardedId
                          AND active_reference_count > 0
                        """)
                .param("guardType", guardType.name())
                .param("guardedId", guardedBusinessId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "活动守卫计数不守恒：" + guardType + "/" + guardedBusinessId + " 已为 0");
        }
    }

    @Override
    public void insertReservation(ReservationRecord record) {
        jdbc.sql("""
                        INSERT INTO workforce_activity_reservations (
                            reservation_business_id, source_module, source_type, source_business_id,
                            reservation_scope_type, reserved_business_id, subject_snapshot_ref,
                            standard_snapshot_ref, work_type_code, work_date, status, reserved_at,
                            released_at, row_version)
                        VALUES (:reservationId, :sourceModule, :sourceType, :sourceBusinessId,
                                :scopeType, :reservedBusinessId, :subjectSnapshotRef,
                                :standardSnapshotRef, :workType, :workDate, :status, UTC_TIMESTAMP(6),
                                NULL, 1)
                        """)
                .param("reservationId", record.reservationBusinessId())
                .param("sourceModule", record.sourceModule())
                .param("sourceType", record.sourceType())
                .param("sourceBusinessId", record.sourceBusinessId())
                .param("scopeType", record.scopeType().name())
                .param("reservedBusinessId", record.reservedBusinessId())
                .param("subjectSnapshotRef", record.subjectSnapshotRef())
                .param("standardSnapshotRef", record.standardSnapshotRef())
                .param("workType", record.workTypeCode())
                .param("workDate", record.workDate() == null ? null : java.sql.Date.valueOf(record.workDate()))
                .param("status", record.status().name())
                .update();
    }

    @Override
    public List<ReservationRecord> findActive(WorkforceActivitySourceRef source) {
        return jdbc.sql(SELECT + """
                         WHERE source_module = :sourceModule AND source_type = :sourceType
                           AND source_business_id = :sourceBusinessId AND status = 'ACTIVE'
                         ORDER BY reservation_scope_type, reserved_business_id
                        """)
                .param("sourceModule", source.sourceModule())
                .param("sourceType", source.sourceType())
                .param("sourceBusinessId", source.sourceBusinessId())
                .query(JdbcActivityReservationRepository::map)
                .list();
    }

    @Override
    public Optional<ReservationRecord> findActiveScope(
            WorkforceActivitySourceRef source, ReservationScopeType scopeType, String reservedBusinessId) {
        return jdbc.sql(SELECT + """
                         WHERE source_module = :sourceModule AND source_type = :sourceType
                           AND source_business_id = :sourceBusinessId
                           AND reservation_scope_type = :scopeType
                           AND reserved_business_id = :reservedBusinessId AND status = 'ACTIVE'
                        """)
                .param("sourceModule", source.sourceModule())
                .param("sourceType", source.sourceType())
                .param("sourceBusinessId", source.sourceBusinessId())
                .param("scopeType", scopeType.name())
                .param("reservedBusinessId", reservedBusinessId)
                .query(JdbcActivityReservationRepository::map)
                .optional();
    }

    @Override
    public boolean release(String reservationBusinessId, java.time.Instant releasedAt) {
        return jdbc.sql("""
                        UPDATE workforce_activity_reservations
                        SET status = 'RELEASED', released_at = :releasedAt,
                            row_version = row_version + 1
                        WHERE reservation_business_id = :reservationId AND status = 'ACTIVE'
                        """)
                .param("releasedAt", JdbcTimes.toUtc(releasedAt))
                .param("reservationId", reservationBusinessId)
                .update() == 1;
    }

    @Override
    public List<ReservationRecord> findAll(WorkforceActivitySourceRef source) {
        return jdbc.sql(SELECT + """
                         WHERE source_module = :sourceModule AND source_type = :sourceType
                           AND source_business_id = :sourceBusinessId
                         ORDER BY reserved_at, reservation_business_id
                        """)
                .param("sourceModule", source.sourceModule())
                .param("sourceType", source.sourceType())
                .param("sourceBusinessId", source.sourceBusinessId())
                .query(JdbcActivityReservationRepository::map)
                .list();
    }

    @Override
    public List<ReservationRecord> findActiveByReserved(
            ReservationScopeType scopeType, String reservedBusinessId) {
        return jdbc.sql(SELECT + """
                         WHERE reservation_scope_type = :scopeType
                           AND reserved_business_id = :reservedBusinessId AND status = 'ACTIVE'
                         ORDER BY work_date, reservation_business_id
                        """)
                .param("scopeType", scopeType.name())
                .param("reservedBusinessId", reservedBusinessId)
                .query(JdbcActivityReservationRepository::map)
                .list();
    }

    /** 生成预留稳定标识。 */
    public static String newReservationId() {
        return "WAR-" + UUID.randomUUID();
    }

    private static final String SELECT = """
            SELECT reservation_business_id, source_module, source_type, source_business_id,
                   reservation_scope_type, reserved_business_id, subject_snapshot_ref,
                   standard_snapshot_ref, work_type_code, work_date, status
            FROM workforce_activity_reservations
            """;

    private static ReservationRecord map(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        java.sql.Date workDate = resultSet.getDate("work_date");
        return new ReservationRecord(
                resultSet.getString("reservation_business_id"),
                resultSet.getString("source_module"),
                resultSet.getString("source_type"),
                resultSet.getString("source_business_id"),
                ReservationScopeType.valueOf(resultSet.getString("reservation_scope_type")),
                resultSet.getString("reserved_business_id"),
                resultSet.getString("subject_snapshot_ref"),
                resultSet.getString("standard_snapshot_ref"),
                resultSet.getString("work_type_code"),
                workDate == null ? null : workDate.toLocalDate(),
                ReservationStatus.valueOf(resultSet.getString("status")));
    }
}
