package com.yumi.production.verification.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 核验事实访问（阶段五 5.7）：一条任务明细最多一条有效核验（唯一外键兜底），
 * 事实不可覆盖、不可物理删除，阶段五不提供生产核验的编辑或冲销接口。
 */
@Repository
public class ProductionVerificationRepository {

    /** 核验事实一行。 */
    public record VerificationRow(
            Long id,
            long taskItemId,
            long taskId,
            long orderId,
            long orderItemId,
            long productId,
            String node,
            int plannedQuantity,
            int completedQuantity,
            int qualifiedQuantity,
            int reworkQuantity,
            int scrapQuantity,
            int incompleteQuantity,
            String verifyNote,
            String verifiedBy,
            String verifiedAt) {
    }

    private static final String COLUMNS = """
            id, task_item_id, task_id, order_id, order_item_id, product_id, node, planned_quantity,
            completed_quantity, qualified_quantity, rework_quantity, scrap_quantity, incomplete_quantity,
            verify_note, verified_by, verified_at
            """;

    private static final RowMapper<VerificationRow> MAPPER = ProductionVerificationRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public ProductionVerificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long taskItemId, long taskId, long orderId, long orderItemId, long productId, String node,
                       int plannedQuantity, int completed, int qualified, int rework, int scrap, int incomplete,
                       String verifyNote, String verifiedBy, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO production_verifications (task_item_id, task_id, order_id, order_item_id, product_id,
                    node, planned_quantity, completed_quantity, qualified_quantity, rework_quantity,
                    scrap_quantity, incomplete_quantity, verify_note, verified_by, verified_at,
                    created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """, taskItemId, taskId, orderId, orderItemId, productId, node, plannedQuantity, completed,
                qualified, rework, scrap, incomplete, verifyNote, verifiedBy, requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_verifications WHERE task_item_id = ?", Long.class, taskItemId);
    }

    public Optional<VerificationRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM production_verifications WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<VerificationRow> findByTaskItem(long taskItemId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM production_verifications WHERE task_item_id = ?", MAPPER, taskItemId)
                .stream().findFirst();
    }

    public List<VerificationRow> findByTask(long taskId) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM production_verifications WHERE task_id = ? ORDER BY id", MAPPER, taskId);
    }

    private static VerificationRow map(ResultSet rs, int rowNum) throws SQLException {
        return new VerificationRow(rs.getLong("id"), rs.getLong("task_item_id"), rs.getLong("task_id"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getLong("product_id"),
                rs.getString("node"), rs.getInt("planned_quantity"), rs.getInt("completed_quantity"),
                rs.getInt("qualified_quantity"), rs.getInt("rework_quantity"), rs.getInt("scrap_quantity"),
                rs.getInt("incomplete_quantity"), rs.getString("verify_note"), rs.getString("verified_by"),
                rs.getString("verified_at"));
    }
}
