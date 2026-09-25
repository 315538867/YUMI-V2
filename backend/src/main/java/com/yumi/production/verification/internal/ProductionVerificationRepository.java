package com.yumi.production.verification.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** `production_verifications` 访问（阶段五）：核验只追加，每计划最多一条（由唯一键与状态共同保证）。 */
@Repository
public class ProductionVerificationRepository {

    public record VerificationRow(long id, long planId, long orderId, long orderItemId, String node,
                                  int completedQuantity, int qualifiedQuantity, int reworkQuantity,
                                  int scrapQuantity, int incompleteQuantity, String verifyNote,
                                  String verifiedBy) {
    }

    private static final String COLUMNS = """
            id, plan_id, order_id, order_item_id, node, completed_quantity, qualified_quantity,
            rework_quantity, scrap_quantity, incomplete_quantity, verify_note, verified_by
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProductionVerificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long planId, long orderId, long orderItemId, String node, int completed, int qualified,
                       int rework, int scrap, int incomplete, String verifyNote, String verifiedBy,
                       String requestId) {
        jdbcTemplate.update("""
                INSERT INTO production_verifications (plan_id, order_id, order_item_id, node,
                    completed_quantity, qualified_quantity, rework_quantity, scrap_quantity,
                    incomplete_quantity, verify_note, verified_by, verified_at, created_at, updated_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6), ?)
                """, planId, orderId, orderItemId, node, completed, qualified, rework, scrap, incomplete,
                verifyNote, verifiedBy, requestId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_verifications WHERE plan_id = ?", Long.class, planId);
    }

    public Optional<VerificationRow> findByPlanId(long planId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_verifications WHERE plan_id = ?",
                this::map, planId).stream().findFirst();
    }

    public Optional<VerificationRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM production_verifications WHERE id = ?",
                this::map, id).stream().findFirst();
    }

    private VerificationRow map(ResultSet rs, int rowNum) throws SQLException {
        return new VerificationRow(rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("order_id"),
                rs.getLong("order_item_id"), rs.getString("node"), rs.getInt("completed_quantity"),
                rs.getInt("qualified_quantity"), rs.getInt("rework_quantity"), rs.getInt("scrap_quantity"),
                rs.getInt("incomplete_quantity"), rs.getString("verify_note"), rs.getString("verified_by"));
    }
}
