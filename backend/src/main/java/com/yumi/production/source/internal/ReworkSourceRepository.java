package com.yumi.production.source.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * `rework_sources` 访问（阶段五）：返工来源与余额。
 * 余额 = `total_quantity − arranged_quantity`；安排与取消都只改 `arranged_quantity`，总量与来源事实不变。
 */
@Repository
public class ReworkSourceRepository {

    public record ReworkSourceRow(long id, long verificationId, long orderId, long orderItemId,
                                  String foundNode, String targetNode, int totalQuantity,
                                  int arrangedQuantity, int roundNo, Long previousSourceId,
                                  String reason, long version) {

        public int balance() {
            return totalQuantity - arrangedQuantity;
        }
    }

    private static final String COLUMNS = """
            id, verification_id, order_id, order_item_id, found_node, target_node, total_quantity,
            arranged_quantity, round_no, previous_source_id, reason, version
            """;

    private final JdbcTemplate jdbcTemplate;

    public ReworkSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long verificationId, long orderId, long orderItemId, String foundNode, String targetNode,
                       int totalQuantity, int roundNo, Long previousSourceId, String reason, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO rework_sources (verification_id, order_id, order_item_id, found_node, target_node,
                    total_quantity, arranged_quantity, round_no, previous_source_id, reason, version,
                    created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, verificationId, orderId, orderItemId, foundNode, targetNode, totalQuantity, roundNo,
                previousSourceId, reason, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM rework_sources WHERE verification_id = ? AND target_node = ?
                """, Long.class, verificationId, targetNode);
    }

    public Optional<ReworkSourceRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rework_sources WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    public Optional<ReworkSourceRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rework_sources WHERE id = ? FOR UPDATE",
                this::map, id).stream().findFirst();
    }

    public List<ReworkSourceRow> findByItem(long orderItemId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM rework_sources WHERE order_item_id = ? ORDER BY id",
                this::map, orderItemId);
    }

    /** 同一次核验的同一目标工序是否已有来源（唯一键的预检，用于返回清晰的 409）。 */
    public Optional<ReworkSourceRow> findByIdForVerificationTarget(long verificationId, String targetNode) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                        + " FROM rework_sources WHERE verification_id = ? AND target_node = ?",
                this::map, verificationId, targetNode).stream().findFirst();
    }

    /** 该核验下所有来源的总量合计（用于校验「返工来源总量不超过该核验的返工数量」）。 */
    public int totalByVerification(long verificationId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_quantity), 0) FROM rework_sources WHERE verification_id = ?",
                Integer.class, verificationId);
        return value == null ? 0 : value;
    }

    /** 计划占用来源余额；调用方已校验不超过余额。 */
    public void arrange(long id, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE rework_sources SET arranged_quantity = arranged_quantity + ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, id);
    }

    /** 取消计划或核验未完成时回退来源余额。 */
    public void release(long id, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE rework_sources SET arranged_quantity = arranged_quantity - ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, id);
    }

    private ReworkSourceRow map(ResultSet rs, int rowNum) throws SQLException {
        long previous = rs.getLong("previous_source_id");
        return new ReworkSourceRow(rs.getLong("id"), rs.getLong("verification_id"), rs.getLong("order_id"),
                rs.getLong("order_item_id"), rs.getString("found_node"), rs.getString("target_node"),
                rs.getInt("total_quantity"), rs.getInt("arranged_quantity"), rs.getInt("round_no"),
                rs.wasNull() ? null : previous, rs.getString("reason"), rs.getLong("version"));
    }
}
