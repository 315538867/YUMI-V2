package com.yumi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务 6.1 发货模块迁移断言：五张表、`SH` 唯一编号、状态枚举、数量与运费检查、
 * 来源追溯唯一、等量更正唯一；Hibernate {@code ddl-auto: validate} 由上下文启动保证。
 */
@SpringBootTest
class ShipmentMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long orderId;
    private long orderItemId;

    @BeforeEach
    void setup() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, version, created_at, updated_at)
                VALUES ('TSH001', 'TST-发货商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TSH001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TSH001', 'TST-发货客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TSH001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TSH0001', ?, 'TST-发货客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TSH0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TSH001', 'TST-发货商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TSH0001'";
        var shipments = "SELECT id FROM shipments WHERE order_id IN (" + testOrders + ")";
        jdbcTemplate.update("DELETE FROM shipment_source_links WHERE shipment_item_id IN "
                + "(SELECT id FROM shipment_items WHERE shipment_id IN (" + shipments + "))");
        jdbcTemplate.update("DELETE FROM shipment_logistics_changes WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipment_corrections WHERE original_shipment_id IN (" + shipments
                + ") OR replacement_shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("UPDATE shipments SET replaces_shipment_id = NULL WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TSH0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TSH001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TSH001'");
    }

    @Test
    void createsShipmentTables() {
        for (var table : new String[]{"shipments", "shipment_items", "shipment_source_links",
                "shipment_logistics_changes", "shipment_corrections"}) {
            assertThat(tableExists(table)).as("缺少表 " + table).isTrue();
        }
    }

    @Test
    void enforcesNumbersStatusAndKeys() {
        assertThat(columnType("shipments", "shipment_no")).isEqualTo("char(8)");
        assertThat(columnType("shipments", "freight")).isEqualTo("decimal(19,4)");
        assertThat(columnType("shipment_items", "quantity")).isEqualTo("int unsigned");
        assertThat(uniqueKeys("shipments")).contains("uk_shipments_shipment_no");
        assertThat(uniqueKeys("shipment_items")).contains("uk_shipment_items_shipment_item");
        assertThat(uniqueKeys("shipment_source_links")).contains("uk_shipment_source_links_source");
        assertThat(uniqueKeys("shipment_corrections")).contains("uk_shipment_corrections_original");
        assertThat(foreignKeys("shipments")).contains("fk_shipments_order", "fk_shipments_replaces");
        assertThat(foreignKeys("shipment_items")).contains("fk_shipment_items_shipment",
                "fk_shipment_items_order_item");
        assertThat(foreignKeys("shipment_source_links")).contains("fk_shipment_source_links_item");
        assertThat(foreignKeys("shipment_logistics_changes")).contains("fk_shipment_logistics_changes_shipment");
        assertThat(foreignKeys("shipment_corrections")).contains("fk_shipment_corrections_original",
                "fk_shipment_corrections_replacement");
    }

    @Test
    void rejectsBadStatusQuantityFreightAndDuplicateCorrection() {
        assertThatThrownBy(() -> insertShipment("SH900001", "UNKNOWN"))
                .as("批次状态受约束")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_shipments_status");
        assertThatThrownBy(() -> insertShipment("SH900002", "DRAFT", new BigDecimal("-1.0000")))
                .as("运费不得为负")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_shipments_freight");

        long first = insertShipment("SH900003", "DRAFT");
        long second = insertShipment("SH900004", "CONFIRMED");
        assertThatThrownBy(() -> insertShipment("SH900003", "DRAFT"))
                .as("发货编号唯一")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_shipments_shipment_no");

        // 发货明细数量必须大于 0，且同一批次同一明细唯一
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 3, 'TSH001', 'TST-发货商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, first, orderItemId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 0, 'TSH001', 'TST-发货商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, first, orderItemId))
                .as("数量必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_shipment_items_quantity");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 1, 'TSH001', 'TST-发货商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, first, orderItemId))
                .as("同一批次同一明细唯一")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_shipment_items_shipment_item");

        // 等量更正：一个原批次最多一次
        jdbcTemplate.update("""
                INSERT INTO shipment_corrections (original_shipment_id, replacement_shipment_id, reason,
                    created_at, updated_at)
                VALUES (?, ?, '录错', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, first, second);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO shipment_corrections (original_shipment_id, replacement_shipment_id, reason,
                    created_at, updated_at)
                VALUES (?, ?, '再更正', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, first, second))
                .as("一个原批次最多一次等量更正")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_shipment_corrections_original");
    }

    // ---------- 工具 ----------

    private long insertShipment(String shipmentNo, String status) {
        return insertShipment(shipmentNo, status, BigDecimal.ZERO);
    }

    private long insertShipment(String shipmentNo, String status, BigDecimal freight) {
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, freight, current_freight,
                    created_at, updated_at)
                VALUES (?, ?, ?, '2026-09-25', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, shipmentNo, orderId, status, freight, freight);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = ?", Long.class, shipmentNo);
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
