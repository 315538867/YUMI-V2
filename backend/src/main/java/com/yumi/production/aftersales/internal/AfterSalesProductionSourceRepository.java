package com.yumi.production.aftersales.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 售后生产来源仓库（阶段八 8.4/8.5）：`after_sales_production_sources` 的读写。
 *
 * <p>唯一键 `(after_sales_item_id, purpose, node)` 保证「同一售后明细 + 同一用途 + 同一目标工序」只有一条来源；
 * 余额 `available_quantity = total_quantity − arranged_quantity` 由来源行派生，
 * 只由创建、安排与释放（取消/未完成回退）改变，与 `rework_sources` 同一套口径。
 * 竞争性读写一律使用 `FOR UPDATE` 锁定读。
 */
@Repository
public class AfterSalesProductionSourceRepository {

    /** 来源一行：`balance` 为读时派生，不落库。 */
    public record SourceRow(long id, long afterSalesItemId, long orderId, long orderItemId, String purpose,
                            String node, int totalQuantity, int arrangedQuantity, String reason, long version) {

        public int availableQuantity() {
            return totalQuantity - arrangedQuantity;
        }
    }

    private static final String COLUMNS = """
            id, after_sales_item_id, order_id, order_item_id, purpose, node, total_quantity, arranged_quantity,
            reason, version
            """;

    private static final RowMapper<SourceRow> MAPPER = AfterSalesProductionSourceRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public AfterSalesProductionSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 某售后明细下的全部来源（按 id 升序）。 */
    public List<SourceRow> findByItem(long afterSalesItemId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM after_sales_production_sources "
                + "WHERE after_sales_item_id = ? ORDER BY id", MAPPER, afterSalesItemId);
    }

    /** 该售后单下所有明细的来源（只读引用售后明细表，用于页面读模型）。 */
    public List<SourceRow> findByCase(long caseId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM after_sales_production_sources "
                + "WHERE after_sales_item_id IN (SELECT id FROM after_sales_items WHERE case_id = ?) ORDER BY id",
                MAPPER, caseId);
    }

    public Optional<SourceRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM after_sales_production_sources WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /** 锁定读来源行：安排/释放来源额度前先锁来源，避免并发超支。 */
    public Optional<SourceRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM after_sales_production_sources WHERE id = ? "
                + "FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    /** 按唯一键锁定来源行：同一售后明细同一用途同一目标工序只有一条来源。 */
    public Optional<SourceRow> findForUpdate(long afterSalesItemId, String purpose, String node) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM after_sales_production_sources "
                + "WHERE after_sales_item_id = ? AND purpose = ? AND node = ? FOR UPDATE", MAPPER,
                afterSalesItemId, purpose, node).stream().findFirst();
    }

    public long insert(long afterSalesItemId, long orderId, long orderItemId, String purpose, String node,
                       int totalQuantity, String reason, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_production_sources (after_sales_item_id, order_id, order_item_id,
                    purpose, node, total_quantity, arranged_quantity, reason, version, created_at, updated_at,
                    request_id)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, afterSalesItemId, orderId, orderItemId, purpose, node, totalQuantity, reason, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM after_sales_production_sources
                WHERE after_sales_item_id = ? AND purpose = ? AND node = ?
                """, Long.class, afterSalesItemId, purpose, node);
    }

    /** 来源额度按需提升（售后补发需求在退回核验后可能增大）。 */
    public void raiseTotal(long id, int totalQuantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE after_sales_production_sources SET total_quantity = ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ? WHERE id = ?
                """, totalQuantity, requestId, id);
    }

    /** 安排售后生产：增加已安排量（余额不足由调用方在锁内先校验）。 */
    public void arrange(long id, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE after_sales_production_sources SET arranged_quantity = arranged_quantity + ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ? WHERE id = ?
                """, quantity, requestId, id);
    }

    /**
     * 释放来源余额（取消售后生产明细或核验未完成回退）。
     * `arranged_quantity` 是无符号列，先取 `GREATEST(已安排, 释放量)` 再相减，避免下溢报错。
     */
    public void release(long id, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE after_sales_production_sources
                SET arranged_quantity = GREATEST(arranged_quantity, ?) - ?, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6), request_id = ? WHERE id = ?
                """, quantity, quantity, requestId, id);
    }

    private static SourceRow map(ResultSet rs, int rowNum) throws SQLException {
        return new SourceRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getLong(10));
    }
}
