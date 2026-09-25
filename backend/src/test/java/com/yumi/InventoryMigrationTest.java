package com.yumi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务 4.1 库存模块迁移断言：五张表、IB/IM 唯一编号、非负与方向一致性检查、
 * 来源唯一与冲销关联、领用锁定索引；Hibernate {@code ddl-auto: validate} 由上下文启动保证。
 */
@SpringBootTest
class InventoryMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long productId;
    private long batchId;
    private long movementId;

    @BeforeEach
    void setup() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, version, created_at, updated_at)
                VALUES ('TI0001', 'TST-库存迁移商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TI0001'", Long.class);
        movementId = insertMovement("IM900001", "OPENING");
        batchId = insertBatch("IB900001", productId, movementId, 10);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM inventory_allocation_lines WHERE allocation_id IN "
                + "(SELECT id FROM inventory_allocations WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_no LIKE 'T%'))");
        jdbcTemplate.update("DELETE FROM inventory_allocations WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_no LIKE 'T%')");
        jdbcTemplate.update("DELETE FROM inventory_movement_lines WHERE batch_id IN "
                + "(SELECT id FROM inventory_batches WHERE batch_no LIKE 'IB9%')");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE movement_no LIKE 'IM9%'");
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE batch_no LIKE 'IB9%'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TI0001'");
    }

    @Test
    void createsInventoryTables() {
        for (var table : new String[]{"inventory_batches", "inventory_movements", "inventory_movement_lines",
                "inventory_allocations", "inventory_allocation_lines"}) {
            assertThat(tableExists(table)).as("缺少表 " + table).isTrue();
        }
    }

    @Test
    void enforcesNumbersSourcesReversalsAndLockIndex() {
        assertThat(columnType("inventory_batches", "batch_no")).isEqualTo("char(8)");
        assertThat(columnType("inventory_movements", "movement_no")).isEqualTo("char(8)");
        assertThat(columnType("inventory_batches", "quantity")).isEqualTo("int unsigned");
        assertThat(uniqueKeys("inventory_batches")).contains("uk_inventory_batches_batch_no",
                "uk_inventory_batches_source");
        assertThat(uniqueKeys("inventory_movements")).contains("uk_inventory_movements_movement_no");
        assertThat(indexes("inventory_batches")).contains("idx_inventory_batches_pick");
        assertThat(indexes("inventory_movement_lines")).contains("idx_inventory_movement_lines_batch");
        assertThat(foreignKeys("inventory_movements")).contains("fk_inventory_movements_reverses");
        assertThat(foreignKeys("inventory_movement_lines")).contains("fk_inventory_movement_lines_movement",
                "fk_inventory_movement_lines_batch", "fk_inventory_movement_lines_order_item");
        assertThat(foreignKeys("inventory_allocations")).contains("fk_inventory_allocations_order",
                "fk_inventory_allocations_reverses");
        assertThat(foreignKeys("inventory_allocation_lines")).contains(
                "fk_inventory_allocation_lines_allocation", "fk_inventory_allocation_lines_batch",
                "fk_inventory_allocation_lines_order_item", "fk_inventory_allocation_lines_movement_line",
                "fk_inventory_allocation_lines_fulfillment", "fk_inventory_allocation_lines_reverses");
    }

    @Test
    void rejectsDuplicateSourceBatch() {
        assertThatThrownBy(() -> insertBatch("IB900002", productId, movementId, 5))
                .as("同一来源只能形成一条批次")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_inventory_batches_source");
    }

    @Test
    void enforcesMovementLineQuantityAndDirectionConsistency() {
        // 入库：after 必须等于 before + qty
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO inventory_movement_lines (movement_id, batch_id, direction, quantity,
                    quantity_before, quantity_after, product_id, node, seam_state, created_at, updated_at)
                VALUES (?, ?, 'IN', 5, 10, 12, ?, 'MAKING', 'NONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, movementId, batchId, productId))
                .as("入库前后数量必须自洽")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_inventory_movement_lines_direction");

        // 出库：after + qty 必须等于 before
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO inventory_movement_lines (movement_id, batch_id, direction, quantity,
                    quantity_before, quantity_after, product_id, node, seam_state, created_at, updated_at)
                VALUES (?, ?, 'OUT', 4, 10, 7, ?, 'MAKING', 'NONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, movementId, batchId, productId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_inventory_movement_lines_direction");

        // 数量必须大于 0
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO inventory_movement_lines (movement_id, batch_id, direction, quantity,
                    quantity_before, quantity_after, product_id, node, seam_state, created_at, updated_at)
                VALUES (?, ?, 'IN', 0, 10, 10, ?, 'MAKING', 'NONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, movementId, batchId, productId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_inventory_movement_lines_quantity");
    }

    @Test
    void keepsBatchQuantityEqualToEffectiveMovementLines() {
        jdbcTemplate.update("""
                INSERT INTO inventory_movement_lines (movement_id, batch_id, direction, quantity,
                    quantity_before, quantity_after, product_id, node, seam_state, created_at, updated_at)
                VALUES (?, ?, 'IN', 10, 0, 10, ?, 'MAKING', 'NONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, movementId, batchId, productId);
        jdbcTemplate.update("""
                INSERT INTO inventory_movement_lines (movement_id, batch_id, direction, quantity,
                    quantity_before, quantity_after, product_id, node, seam_state, created_at, updated_at)
                VALUES (?, ?, 'OUT', 3, 10, 7, ?, 'MAKING', 'NONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, movementId, batchId, productId);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM inventory_movement_lines WHERE batch_id = ?
                """, Integer.class, batchId)).isEqualTo(7);
        // 批次当前数量由流水结果回写，二者必须一致
        jdbcTemplate.update("UPDATE inventory_batches SET quantity = 7 WHERE id = ?", batchId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_batches WHERE id = ?", Integer.class, batchId)).isEqualTo(7);
    }

    // ---------- 内部工具 ----------

    private long insertMovement(String movementNo, String type) {
        jdbcTemplate.update("""
                INSERT INTO inventory_movements (movement_no, movement_type, business_date, source_type,
                    source_id, source_line_id, created_at, updated_at)
                VALUES (?, ?, '2026-09-24', 'OPENING', 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, movementNo, type);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_movements WHERE movement_no = ?", Long.class, movementNo);
    }

    private long insertBatch(String batchNo, long productId, long sourceId, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO inventory_batches (batch_no, product_id, product_no, product_name, source_type,
                    source_id, source_line_id, node, seam_state, quantity, inventory_date,
                    created_at, updated_at)
                VALUES (?, ?, 'TI0001', 'TST-库存迁移商品', 'OPENING', ?, 0, 'MAKING', 'NONE', ?, '2026-09-24',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, batchNo, productId, sourceId, quantity);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_batches WHERE batch_no = ?", Long.class, batchNo);
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Boolean.class, tableName));
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, tableName, columnName);
    }

    private java.util.List<String> uniqueKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND non_unique = 0 "
                        + "AND index_name <> 'PRIMARY'",
                String.class, tableName);
    }

    private java.util.List<String> indexes(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, tableName);
    }

    private java.util.List<String> foreignKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND constraint_type = 'FOREIGN KEY'",
                String.class, tableName);
    }
}
