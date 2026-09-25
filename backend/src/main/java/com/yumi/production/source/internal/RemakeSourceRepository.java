package com.yumi.production.source.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * `remake_sources` 访问（阶段五）：报废重做来源与余额。
 * 原报废事实永久保留；重做不恢复原报废数量、不增加订单需求。
 */
@Repository
public class RemakeSourceRepository {

    public record RemakeSourceRow(long id, long verificationId, long orderId, long orderItemId,
                                  String scrapNode, String startNode, int totalQuantity,
                                  int arrangedQuantity, String reason, long version) {

        public int balance() {
            return totalQuantity - arrangedQuantity;
        }
    }

    private static final String COLUMNS = """
            id, verification_id, order_id, order_item_id, scrap_node, start_node, total_quantity,
            arranged_quantity, reason, version
            """;

    private final JdbcTemplate jdbcTemplate;

    public RemakeSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long verificationId, long orderId, long orderItemId, String scrapNode, String startNode,
                       int totalQuantity, String reason, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO remake_sources (verification_id, order_id, order_item_id, scrap_node, start_node,
                    total_quantity, arranged_quantity, reason, version, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, verificationId, orderId, orderItemId, scrapNode, startNode, totalQuantity, reason, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM remake_sources WHERE verification_id = ? AND start_node = ?
                """, Long.class, verificationId, startNode);
    }

    public Optional<RemakeSourceRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM remake_sources WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    public Optional<RemakeSourceRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM remake_sources WHERE id = ? FOR UPDATE",
                this::map, id).stream().findFirst();
    }

    public List<RemakeSourceRow> findByItem(long orderItemId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM remake_sources WHERE order_item_id = ? ORDER BY id",
                this::map, orderItemId);
    }

    /** 同一次核验的同一重做起始工序是否已有来源（唯一键的预检，用于返回清晰的 409）。 */
    public Optional<RemakeSourceRow> findByIdForVerificationTarget(long verificationId, String startNode) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                        + " FROM remake_sources WHERE verification_id = ? AND start_node = ?",
                this::map, verificationId, startNode).stream().findFirst();
    }

    /** 该核验下所有重做来源的总量合计（用于校验「重做来源总量不超过该核验的报废数量」）。 */
    public int totalByVerification(long verificationId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_quantity), 0) FROM remake_sources WHERE verification_id = ?",
                Integer.class, verificationId);
        return value == null ? 0 : value;
    }

    public void arrange(long id, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE remake_sources SET arranged_quantity = arranged_quantity + ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, id);
    }

    public void release(long id, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE remake_sources SET arranged_quantity = arranged_quantity - ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, id);
    }

    private RemakeSourceRow map(ResultSet rs, int rowNum) throws SQLException {
        return new RemakeSourceRow(rs.getLong("id"), rs.getLong("verification_id"), rs.getLong("order_id"),
                rs.getLong("order_item_id"), rs.getString("scrap_node"), rs.getString("start_node"),
                rs.getInt("total_quantity"), rs.getInt("arranged_quantity"), rs.getString("reason"),
                rs.getLong("version"));
    }
}
