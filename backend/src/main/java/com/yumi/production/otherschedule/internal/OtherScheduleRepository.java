package com.yumi.production.otherschedule.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * `other_schedules` 与工时核验/更正访问（阶段五）：其他排班只保存总分钟与工时事实，
 * 不产生商品、库存或订单履约事实。有效工时 = 原核验 + 最新更正。
 */
@Repository
public class OtherScheduleRepository {

    public record OtherScheduleRow(long id, String scheduleNo, LocalDate scheduleDate, long employeeId,
                                   String employeeName, int hours, int minutes, int totalMinutes, String status,
                                   String note, String cancelReason, long version) {
    }

    public record VerificationRow(long id, long scheduleId, int totalMinutes, String verifiedBy) {
    }

    private static final String COLUMNS = """
            id, schedule_no, schedule_date, employee_id, employee_name, hours, minutes, total_minutes,
            status, note, cancel_reason, version
            """;

    private final JdbcTemplate jdbcTemplate;

    public OtherScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(String scheduleNo, LocalDate scheduleDate, long employeeId, String employeeName, int hours,
                       int minutes, int totalMinutes, String note, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO other_schedules (schedule_no, schedule_date, employee_id, employee_name, hours,
                    minutes, total_minutes, status, note, version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, scheduleNo, Date.valueOf(scheduleDate), employeeId, employeeName, hours, minutes, totalMinutes,
                note, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM other_schedules WHERE schedule_no = ?", Long.class, scheduleNo);
    }

    public Optional<OtherScheduleRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM other_schedules WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    public Optional<OtherScheduleRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM other_schedules WHERE id = ? FOR UPDATE",
                this::map, id).stream().findFirst();
    }

    public List<OtherScheduleRow> find(LocalDate dateFrom, LocalDate dateTo, Long employeeId, String status) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM other_schedules WHERE 1=1");
        var args = new ArrayList<Object>();
        if (dateFrom != null) {
            sql.append(" AND schedule_date >= ?");
            args.add(Date.valueOf(dateFrom));
        }
        if (dateTo != null) {
            sql.append(" AND schedule_date <= ?");
            args.add(Date.valueOf(dateTo));
        }
        if (employeeId != null) {
            sql.append(" AND employee_id = ?");
            args.add(employeeId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY schedule_date, id");
        return jdbcTemplate.query(sql.toString(), this::map, args.toArray());
    }

    public void markVerified(long id, String requestId) {
        jdbcTemplate.update("""
                UPDATE other_schedules SET status = 'VERIFIED', version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, requestId, id);
    }

    public void markCancelled(long id, String cancelledBy, String reason, String requestId) {
        jdbcTemplate.update("""
                UPDATE other_schedules SET status = 'CANCELLED', cancelled_at = UTC_TIMESTAMP(6),
                    cancelled_by = ?, cancel_reason = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, cancelledBy, reason, requestId, id);
    }

    public long insertVerification(long scheduleId, int totalMinutes, String verifiedBy, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO other_schedule_verifications (schedule_id, total_minutes, verified_by, verified_at,
                    created_at, updated_at, request_id)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, scheduleId, totalMinutes, verifiedBy, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM other_schedule_verifications WHERE schedule_id = ?", Long.class, scheduleId);
    }

    public Optional<VerificationRow> findVerification(long scheduleId) {
        return jdbcTemplate.query("""
                SELECT id, schedule_id, total_minutes, verified_by FROM other_schedule_verifications
                WHERE schedule_id = ?
                """, (rs, rowNum) -> new VerificationRow(rs.getLong("id"), rs.getLong("schedule_id"),
                rs.getInt("total_minutes"), rs.getString("verified_by")), scheduleId).stream().findFirst();
    }

    public long insertCorrection(long verificationId, long scheduleId, int beforeMinutes, int afterMinutes,
                                 String reason, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO other_schedule_time_corrections (verification_id, schedule_id, before_total_minutes,
                    after_total_minutes, reason, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, verificationId, scheduleId, beforeMinutes, afterMinutes, reason, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM other_schedule_time_corrections WHERE schedule_id = ? ORDER BY id DESC LIMIT 1
                """, Long.class, scheduleId);
    }

    /** 有效工时：有更正取最新更正的 after，否则取原核验的 total。 */
    public Integer effectiveMinutes(long scheduleId) {
        return jdbcTemplate.query("""
                SELECT COALESCE(
                    (SELECT after_total_minutes FROM other_schedule_time_corrections
                     WHERE schedule_id = ? ORDER BY id DESC LIMIT 1),
                    (SELECT total_minutes FROM other_schedule_verifications WHERE schedule_id = ?)
                )
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            // MySQL INT UNSIGNED 经驱动可能返回 Long，统一用 getInt 取值
            int value = rs.getInt(1);
            return rs.wasNull() ? null : value;
        }, scheduleId, scheduleId);
    }

    private OtherScheduleRow map(ResultSet rs, int rowNum) throws SQLException {
        return new OtherScheduleRow(rs.getLong("id"), rs.getString("schedule_no"),
                rs.getDate("schedule_date").toLocalDate(), rs.getLong("employee_id"), rs.getString("employee_name"),
                rs.getInt("hours"), rs.getInt("minutes"), rs.getInt("total_minutes"), rs.getString("status"),
                rs.getString("note"), rs.getString("cancel_reason"), rs.getLong("version"));
    }
}
