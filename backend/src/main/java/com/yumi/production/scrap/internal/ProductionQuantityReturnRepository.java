package com.yumi.production.scrap.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 报废事实与数量回转访问（阶段五 5.12）：两者都是不可变事实，
 * 每条报废恰好一条回转；回转余额只能被后续 `NORMAL` 明细按发生工序分配。
 */
@Repository
public class ProductionQuantityReturnRepository {

    private static final String COLUMNS = """
            id, scrap_record_id, order_id, order_item_id, product_id, node, returned_quantity,
            allocated_quantity, version
            """;

    private static final RowMapper<ProductionQuantityReturnRow> MAPPER =
            ProductionQuantityReturnRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public ProductionQuantityReturnRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写报废事实，返回主键。 */
    public long insertScrap(long verificationId, long taskItemId, long orderId, long orderItemId, long productId,
                            String node, int scrapQuantity, String reason, String operatorUsername,
                            String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO scrap_records (verification_id, task_item_id, order_id, order_item_id, product_id,
                    node, scrap_quantity, reason, operator_username, recorded_at,
                    created_at, updated_at, request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """, verificationId, taskItemId, orderId, orderItemId, productId, node, scrapQuantity, reason,
                operatorUsername, requestId, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM scrap_records WHERE verification_id = ?", Long.class, verificationId);
    }

    /** 写与报废一对一的数量回转事实。 */
    public void insertReturn(long scrapRecordId, long orderId, long orderItemId, long productId, String node,
                             int quantity, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO production_quantity_returns (scrap_record_id, order_id, order_item_id, product_id,
                    node, returned_quantity, allocated_quantity, version, created_at, updated_at,
                    request_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """, scrapRecordId, orderId, orderItemId, productId, node, quantity, requestId, idempotencyKey);
    }

    public Optional<ProductionQuantityReturnRow> findByIdForUpdate(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM production_quantity_returns WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /** 该订单明细 + 工序上可分配的回转余额（锁内重算用）。 */
    public int availableByItemNode(long orderItemId, String node) {
        var total = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(returned_quantity - allocated_quantity), 0)
                FROM production_quantity_returns WHERE order_item_id = ? AND node = ?
                """, Integer.class, orderItemId, node);
        return total == null ? 0 : total;
    }

    /** 锁定该订单明细 + 工序上的回转行（按 id 升序），供核验与创建在锁内重算余额。 */
    public List<ProductionQuantityReturnRow> lockByItemNode(long orderItemId, String node) {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM production_quantity_returns WHERE order_item_id = ? AND node = ? ORDER BY id FOR UPDATE",
                MAPPER, orderItemId, node);
    }

    public void allocate(long returnId, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE production_quantity_returns SET allocated_quantity = allocated_quantity + ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, returnId);
    }

    public void release(long returnId, int quantity, String requestId) {
        jdbcTemplate.update("""
                UPDATE production_quantity_returns SET allocated_quantity = GREATEST(0, allocated_quantity - ?),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ?
                """, quantity, requestId, returnId);
    }

    public List<ProductionQuantityReturnRow> find(Long orderItemId, String node) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM production_quantity_returns WHERE 1=1");
        var args = new ArrayList<Object>();
        if (orderItemId != null) {
            sql.append(" AND order_item_id = ?");
            args.add(orderItemId);
        }
        if (node != null && !node.isBlank()) {
            sql.append(" AND node = ?");
            args.add(node.trim());
        }
        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    private static ProductionQuantityReturnRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ProductionQuantityReturnRow(rs.getLong("id"), rs.getLong("scrap_record_id"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getLong("product_id"),
                rs.getString("node"), rs.getInt("returned_quantity"), rs.getInt("allocated_quantity"),
                rs.getLong("version"));
    }
}
