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
 * 任务 3.1 订单模块迁移断言：八张订单表、业务编号唯一、Q/E 检查、优惠上限、
 * 变更明细目标一致性、来源唯一消费与关键索引；Hibernate {@code ddl-auto: validate} 由上下文启动保证。
 */
@SpringBootTest
class OrderMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long customerId;
    private long productId;
    private long orderId;
    private long orderItemId;

    @BeforeEach
    void setup() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, default_recipient, default_recipient_phone,
                    default_region, default_address, version, created_at, updated_at)
                VALUES ('TC0001', 'TST-订单迁移客户', '收货人', '13800000000', '华东', '测试地址', 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TC0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, version, created_at, updated_at)
                VALUES ('TP0001', 'TST-订单迁移商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TP0001'", Long.class);
        orderId = insertOrder("T000001");
        orderItemId = insertItem(orderId, 1, 10, 0);
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no LIKE 'T%'";
        jdbcTemplate.update("DELETE FROM order_change_items WHERE change_order_id IN "
                + "(SELECT id FROM order_change_orders WHERE order_id IN (" + testOrders + "))");
        for (var table : new String[]{"order_inventory_plan_lines", "fulfillment_entries",
                "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_change_orders", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE order_no LIKE 'T%'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TP0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TC0001'");
    }

    @Test
    void createsOrderModuleTables() {
        for (var table : new String[]{"orders", "order_items", "order_confirmation_snapshots",
                "order_item_snapshots", "order_change_orders", "order_change_items",
                "fulfillment_entries", "order_item_fulfillment_balances"}) {
            assertThat(tableExists(table)).as("缺少表 " + table).isTrue();
        }
    }

    @Test
    void createsDraftInventoryPlanTable() {
        assertThat(tableExists("order_inventory_plan_lines")).isTrue();
        assertThat(columnType("order_inventory_plan_lines", "quantity")).isEqualTo("int unsigned");
        assertThat(isNullable("order_inventory_plan_lines", "batch_id")).isFalse();
        assertThat(uniqueKeys("order_inventory_plan_lines")).contains("uk_order_inventory_plan_lines_target");
        assertThat(foreignKeys("order_inventory_plan_lines")).contains("fk_order_inventory_plan_lines_order",
                "fk_order_inventory_plan_lines_item", "fk_order_inventory_plan_lines_batch");
        // 计划不占用库存：批次当前数量只由库存流水改动，计划表没有库存余额列
        assertThat(columnNames("order_inventory_plan_lines")).doesNotContain("allocated_quantity");
    }

    @Test
    void enforcesBusinessNumbersVersionsAndUniqueKeys() {
        assertThat(columnType("orders", "order_no")).isEqualTo("char(7)");
        assertThat(columnType("orders", "goods_amount")).isEqualTo("decimal(19,4)");
        assertThat(columnType("orders", "version")).isEqualTo("bigint unsigned");
        assertThat(columnType("orders", "expected_delivery_date")).isEqualTo("date");
        assertThat(isNullable("orders", "expected_delivery_date")).isTrue();
        assertThat(columnType("order_items", "quantity")).isEqualTo("int unsigned");
        assertThat(columnType("order_change_orders", "change_no")).isEqualTo("char(7)");
        assertThat(columnType("fulfillment_entries", "source_line_id")).isEqualTo("bigint unsigned");
        assertThat(isNullable("fulfillment_entries", "source_line_id")).isFalse();
        assertThat(columnType("order_item_fulfillment_balances", "required_quantity")).isEqualTo("int unsigned");

        assertThat(uniqueKeys("orders")).contains("uk_orders_order_no");
        assertThat(uniqueKeys("order_items")).contains("uk_order_items_order_line");
        assertThat(uniqueKeys("order_confirmation_snapshots")).contains("uk_order_confirmation_snapshots_order");
        assertThat(uniqueKeys("order_item_snapshots")).contains("uk_order_item_snapshots_item");
        assertThat(uniqueKeys("order_change_orders")).contains("uk_order_change_orders_change_no");
        assertThat(uniqueKeys("order_item_fulfillment_balances"))
                .contains("uk_order_item_fulfillment_balances_item");
        assertThat(uniqueKeys("fulfillment_entries")).contains("uk_fulfillment_entries_source");

        assertThat(indexes("orders")).contains("idx_orders_status", "idx_orders_customer", "idx_orders_order_date");
        assertThat(indexes("order_items")).contains("idx_order_items_order", "idx_order_items_product");
        assertThat(indexes("fulfillment_entries")).contains("idx_fulfillment_entries_item");
        assertThat(foreignKeys("orders")).contains("fk_orders_customer");
        assertThat(foreignKeys("order_items")).contains("fk_order_items_order", "fk_order_items_product",
                "fk_order_items_seam_type");
        assertThat(foreignKeys("order_change_items")).contains("fk_order_change_items_change_order");
    }

    @Test
    void rejectsSeamQuantityAboveQuantity() {
        assertThatThrownBy(() -> insertItem(orderId, 2, 10, 11))
                .as("缝边数量不得超过明细数量")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_order_items_seam_within_quantity");
    }

    @Test
    void rejectsDiscountAboveGoodsPlusSeam() {
        jdbcTemplate.update("UPDATE orders SET goods_amount = 100.0000, seam_amount = 20.0000 WHERE id = ?", orderId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE orders SET discount_amount = 130.0000 WHERE id = ?", orderId))
                .as("优惠不得超过商品金额 + 缝边收费")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_orders_discount_within_goods");
    }

    @Test
    void rejectsChangeItemWithInconsistentTarget() {
        long changeOrderId = insertChangeOrder("T000101");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO order_change_items (change_order_id, order_item_id, change_type, product_id,
                    after_quantity, created_at, updated_at)
                VALUES (?, ?, 'ADD', ?, 5, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, changeOrderId, orderItemId, productId))
                .as("新增行不得携带已有明细")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_order_change_items_target");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO order_change_items (change_order_id, order_item_id, change_type,
                    after_quantity, created_at, updated_at)
                VALUES (?, NULL, 'UPDATE', 5, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, changeOrderId))
                .as("改单/移除必须指向已有明细")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_order_change_items_target");
    }

    @Test
    void rejectsDuplicateSourceConsumption() {
        insertEntry("ORDER_DEMAND", 1001L, "SHIPPABLE", "IN", 10);
        assertThatThrownBy(() -> insertEntry("ORDER_DEMAND", 1001L, "SHIPPABLE", "IN", 10))
                .as("同一来源记录+来源明细+节点+方向只能入账一次")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_fulfillment_entries_source");
        // 换方向或换节点视为不同入账，允许
        insertEntry("ORDER_DEMAND", 1001L, "SHIPPABLE", "OUT", 10);
        insertEntry("ORDER_DEMAND", 1001L, "MAKING", "IN", 10);
        // 无明细来源（source_line_id = 0）同样受唯一键约束
        insertEntryWithLine("ORDER_DEMAND", 1002L, 0L, "SHIPPABLE", "IN", 3);
        assertThatThrownBy(() -> insertEntryWithLine("ORDER_DEMAND", 1002L, 0L, "SHIPPABLE", "IN", 3))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_fulfillment_entries_source");
    }

    // ---------- 内部工具 ----------

    private long insertOrder(String orderNo) {
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date,
                    recipient_name, recipient_phone, region, address, version, created_at, updated_at)
                VALUES (?, ?, 'TST-订单迁移客户', 'DRAFT', '2026-09-24', '收货人', '13800000000',
                    '华东', '测试地址', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderNo, customerId);
        return jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
    }

    private long insertItem(long orderId, int lineNo, int quantity, int seamQuantity) {
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, 'TP0001', 'TST-订单迁移商品', ?, ?, 10.0000, 100.0000, 5.0000, 50.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, lineNo, productId, quantity, seamQuantity);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = ?", Long.class, orderId, lineNo);
    }

    private long insertChangeOrder(String changeNo) {
        jdbcTemplate.update("""
                INSERT INTO order_change_orders (change_no, order_id, status, version, created_at, updated_at)
                VALUES (?, ?, 'DRAFT', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, changeNo, orderId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM order_change_orders WHERE change_no = ?", Long.class, changeNo);
    }

    private void insertEntry(String entryType, long sourceId, String node, String direction, int quantity) {
        insertEntryWithLine(entryType, sourceId, orderItemId, node, direction, quantity);
    }

    private void insertEntryWithLine(String entryType, long sourceId, long sourceLineId, String node,
                                     String direction, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ORDER', ?, ?, '2026-09-24', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId, entryType, node, direction, quantity, sourceId, sourceLineId);
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

    private boolean isNullable(String tableName, String columnName) {
        return "YES".equals(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, tableName, columnName));
    }

    private java.util.List<String> columnNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, tableName);
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
