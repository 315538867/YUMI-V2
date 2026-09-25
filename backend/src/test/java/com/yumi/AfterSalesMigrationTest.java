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
 * 任务 8.1 售后迁移断言：六张表、`AS` 唯一编号、退回等式、原发货明细必填引用与唯一占用、
 * 售后台账来源唯一；Hibernate {@code ddl-auto: validate} 由上下文启动保证。
 */
@SpringBootTest
class AfterSalesMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long orderId;
    private long orderItemId;
    private long shipmentItemId;
    private long caseId;
    private long afterSalesItemId;

    @BeforeEach
    void setup() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, version, created_at, updated_at)
                VALUES ('TAM001', 'TST-售后迁移商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TAM001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TAM001', 'TST-售后迁移客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TAM001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TAM0001', ?, 'TST-售后迁移客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TAM0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TAM001', 'TST-售后迁移商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, freight, current_freight,
                    created_at, updated_at)
                VALUES ('SH950001', ?, 'CONFIRMED', '2026-09-25', 0.0000, 0.0000, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, orderId);
        long shipmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = 'SH950001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 4, 'TAM001', 'TST-售后迁移商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, shipmentId, orderItemId);
        shipmentItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = ?", Long.class, shipmentId);

        caseId = insertCase("AS950001");
        afterSalesItemId = insertItem(caseId, 4);
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TAM0001'";
        var cases = "SELECT id FROM after_sales_cases WHERE order_id IN (" + testOrders + ")";
        var items = "SELECT id FROM after_sales_items WHERE case_id IN (" + cases + ")";
        jdbcTemplate.update("DELETE FROM after_sales_production_sources WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_corrections WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_fulfillment_entries WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_shipment_links WHERE after_sales_item_id IN (" + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_return_verifications WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_items WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE order_id IN (" + testOrders + ")");
        var shipments = "SELECT id FROM shipments WHERE order_id IN (" + testOrders + ")";
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TAM0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TAM001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TAM001'");
    }

    @Test
    void createsAfterSalesTables() {
        for (var table : new String[]{"after_sales_cases", "after_sales_items",
                "after_sales_return_verifications", "after_sales_fulfillment_entries",
                "after_sales_shipment_links", "after_sales_corrections",
                "after_sales_production_sources"}) {
            assertThat(tableExists(table)).as("缺少表 " + table).isTrue();
        }
    }

    @Test
    void enforcesAfterSalesProductionSourceConstraints() {
        // 来源额度：整数无符号、用途枚举、来源唯一（同一售后明细同一用途同一工序）
        assertThat(columnType("after_sales_production_sources", "total_quantity")).isEqualTo("int unsigned");
        assertThat(uniqueKeys("after_sales_production_sources"))
                .contains("uk_after_sales_production_sources_target");
        assertThat(foreignKeys("after_sales_production_sources")).contains(
                "fk_after_sales_production_sources_after_sales_item",
                "fk_after_sales_production_sources_order",
                "fk_after_sales_production_sources_item");

        long itemId = afterSalesItemId;
        insertProductionSource(itemId, "REWORK", "MAKING", 4);
        assertThatThrownBy(() -> insertProductionSource(itemId, "REWORK", "MAKING", 2))
                .as("同一售后明细同一用途同一工序只能有一条来源")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_after_sales_production_sources_target");
        // 用途枚举受 CHECK 约束
        assertThatThrownBy(() -> insertProductionSource(itemId, "SCRAP", "MAKING", 2))
                .as("用途必须是 REWORK 或 REPLACEMENT")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_after_sales_production_sources_purpose");
        // 安排数量不得超过总额度
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO after_sales_production_sources (after_sales_item_id, order_id, order_item_id,
                    purpose, node, total_quantity, arranged_quantity, version, created_at, updated_at)
                VALUES (?, (SELECT order_id FROM after_sales_items WHERE id = ?),
                    (SELECT order_item_id FROM after_sales_items WHERE id = ?), 'REPLACEMENT', 'MAKING', 2, 3,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, itemId, itemId, itemId))
                .as("安排数量不得超过总额度")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_after_sales_production_sources_quantity");
    }

    @Test
    void enforcesNumbersSourceUniquenessAndReturnEquation() {
        assertThat(columnType("after_sales_cases", "case_no")).isEqualTo("char(8)");
        assertThat(columnType("after_sales_items", "accepted_quantity")).isEqualTo("int unsigned");
        assertThat(uniqueKeys("after_sales_cases")).contains("uk_after_sales_cases_case_no");
        assertThat(uniqueKeys("after_sales_items")).contains("uk_after_sales_items_shipment_item");
        assertThat(uniqueKeys("after_sales_return_verifications"))
                .contains("uk_after_sales_return_verifications_item");
        assertThat(uniqueKeys("after_sales_fulfillment_entries")).contains("uk_after_sales_entries_source");
        assertThat(uniqueKeys("after_sales_shipment_links")).contains("uk_after_sales_shipment_links_item");
        assertThat(foreignKeys("after_sales_items")).contains("fk_after_sales_items_shipment_item",
                "fk_after_sales_items_order_item");
        assertThat(foreignKeys("after_sales_fulfillment_entries")).contains("fk_after_sales_entries_item");
        assertThat(foreignKeys("after_sales_shipment_links")).contains(
                "fk_after_sales_shipment_links_shipment_item");

        // 售后编号唯一
        assertThatThrownBy(() -> insertCase("AS950001"))
                .as("售后编号唯一")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_after_sales_cases_case_no");
        // 同一发货批次明细只允许一个有效售后占用
        assertThatThrownBy(() -> insertItem(caseId, 1))
                .as("同一发货明细只允许一个有效售后占用")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_after_sales_items_shipment_item");
        // 受理数量必须大于 0（用另一个已确认批次的明细，避免撞「同批次同明细」唯一键）
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, freight, current_freight,
                    created_at, updated_at)
                VALUES ('SH950002', ?, 'CONFIRMED', '2026-09-25', 0.0000, 0.0000, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, orderId);
        long secondShipmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = 'SH950002'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 2, 'TAM001', 'TST-售后迁移商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, secondShipmentId, orderItemId);
        long secondShipmentItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = ?", Long.class, secondShipmentId);
        assertThatThrownBy(() -> insertItemWithQuantity(caseId, secondShipmentItemId, 0))
                .as("受理数量必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_after_sales_items_accepted");
    }

    @Test
    void enforcesReturnEquationAndEntryUniqueness() {
        // 退回 ≠ 返工 + 报废
        assertThatThrownBy(() -> insertReturnVerification(afterSalesItemId, 3, 2, 0))
                .as("退回必须等于返工 + 报废")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_after_sales_return_equation");
        insertReturnVerification(afterSalesItemId, 3, 2, 1);
        assertThatThrownBy(() -> insertReturnVerification(afterSalesItemId, 1, 1, 0))
                .as("每售后明细一条退回核验")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_after_sales_return_verifications_item");

        // 每笔来源只接入一次
        insertEntry(afterSalesItemId, "INVENTORY_INFLOW", "IN", 4, "INVENTORY_ALLOCATION", 1);
        assertThatThrownBy(() -> insertEntry(afterSalesItemId, "INVENTORY_INFLOW", "IN", 4,
                        "INVENTORY_ALLOCATION", 1))
                .as("同一来源只接入一次")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_after_sales_entries_source");
    }

    // ---------- 工具 ----------

    private long insertCase(String caseNo) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_cases (case_no, order_id, case_type, status, problem, created_at,
                    updated_at)
                VALUES (?, ?, 'REWORK_AND_REPLACEMENT', 'OPEN', '客户反馈破损', UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, caseNo, orderId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_cases WHERE case_no = ?", Long.class, caseNo);
    }

    private long insertItem(long caseId, int accepted) {
        return insertItemWithQuantity(caseId, shipmentItemId, accepted);
    }

    private long insertItemWithQuantity(long caseId, long shipmentItem, int accepted) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_items (case_id, order_id, order_item_id, shipment_item_id, product_no,
                    product_name, accepted_quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TAM001', 'TST-售后迁移商品', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, caseId, orderId, orderItemId, shipmentItem, accepted);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_items WHERE shipment_item_id = ?", Long.class, shipmentItem);
    }

    private void insertProductionSource(long itemId, String purpose, String node, int total) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_production_sources (after_sales_item_id, order_id, order_item_id,
                    purpose, node, total_quantity, arranged_quantity, version, created_at, updated_at)
                VALUES (?, (SELECT order_id FROM after_sales_items WHERE id = ?),
                    (SELECT order_item_id FROM after_sales_items WHERE id = ?), ?, ?, ?, 0, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, itemId, itemId, itemId, purpose, node, total);
    }

    private void insertReturnVerification(long itemId, int returned, int rework, int scrap) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_return_verifications (after_sales_item_id, returned_quantity,
                    rework_quantity, scrap_quantity, verified_by, verified_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'tester', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, itemId, returned, rework, scrap);
    }

    private void insertEntry(long itemId, String entryType, String direction, int quantity, String sourceType,
                             long sourceId) {
        jdbcTemplate.update("""
                INSERT INTO after_sales_fulfillment_entries (after_sales_item_id, entry_type, direction,
                    quantity, source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, '2026-09-25', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, itemId, entryType, direction, quantity, sourceType, sourceId);
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

    private java.util.List<String> foreignKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND constraint_type = 'FOREIGN KEY'",
                String.class, tableName);
    }
}
