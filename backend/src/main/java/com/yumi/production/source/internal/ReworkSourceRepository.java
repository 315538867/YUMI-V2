package com.yumi.production.source.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 返工来源访问（阶段五 5.9–5.11）：来源只能由管理员基于已存在的返工事实显式创建，
 * 余额只由创建、安排、取消与未完成回退改变；竞争性读写一律使用锁定读。
 */
@Repository
public class ReworkSourceRepository {

    private static final String COLUMNS = """
            id, source_no, origin_verification_id, origin_task_item_id, order_id, order_item_id, product_id,
            node, total_quantity, arranged_quantity, round_no, previous_source_id, reason, version
            """;

    private static final RowMapper<ReworkSourceRow> MAPPER = ReworkSourceRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public ReworkSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(ReworkSourceRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO rework_sources (source_no, origin_verification_id, origin_task_item_id, order_id,
                    order_item_id, product_id, node, total_quantity, arranged_quantity, round_no,
                    previous_source_id, reason, version, created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.sourceNo(), row.originVerificationId(), row.originTaskItemId(), row.orderId(),
                row.orderItemId(), row.productId(), row.node(), row.totalQuantity(), row.roundNo(),
                row.previousSourceId(), row.reason(), requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM rework_sources WHERE source_no = ?", Long.class, row.sourceNo());
    }

    public Optional<ReworkSourceRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rework_sources WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ReworkSourceRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rework_sources WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /** 同一原核验 + 工序 + 轮次上已创建的来源（用于重算「未建来源额度」）。 */
    public List<ReworkSourceRow> findByOriginVerificationForUpdate(long verificationId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM rework_sources WHERE origin_verification_id = ? ORDER BY id FOR UPDATE",
                MAPPER, verificationId);
    }

    public List<ReworkSourceRow> findByOriginVerification(long verificationId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM rework_sources WHERE origin_verification_id = ? ORDER BY id", MAPPER, verificationId);
    }

    public List<ReworkSourceRow> find(Long orderItemId, String node, String status) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM rework_sources WHERE 1=1");
        var args = new ArrayList<Object>();
        if (orderItemId != null) {
            sql.append(" AND order_item_id = ?");
            args.add(orderItemId);
        }
        if (node != null && !node.isBlank()) {
            sql.append(" AND node = ?");
            args.add(node.trim());
        }
        if ("OPEN".equals(status)) {
            sql.append(" AND arranged_quantity < total_quantity");
        } else if ("CLOSED".equals(status)) {
            sql.append(" AND arranged_quantity >= total_quantity");
        }
        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 安排返工：增加已安排量（余额不足由调用方在锁内先校验）。 */
    public void arrange(long sourceId, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE rework_sources SET arranged_quantity = arranged_quantity + ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, sourceId);
    }

    /** 恢复来源余额：取消返工明细或核验未完成时回退已安排量。 */
    public void release(long sourceId, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE rework_sources SET arranged_quantity = GREATEST(0, arranged_quantity - ?),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, sourceId);
    }

    private static ReworkSourceRow map(ResultSet rs, int rowNum) throws SQLException {
        long previous = rs.getLong("previous_source_id");
        boolean previousNull = rs.wasNull();
        return new ReworkSourceRow(rs.getLong("id"), rs.getString("source_no"),
                rs.getLong("origin_verification_id"), rs.getLong("origin_task_item_id"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getLong("product_id"),
                rs.getString("node"), rs.getInt("total_quantity"), rs.getInt("arranged_quantity"),
                rs.getInt("round_no"), previousNull ? null : previous, rs.getString("reason"),
                rs.getLong("version"));
    }
}
