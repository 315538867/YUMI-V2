package com.yumi;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 4.12 MySQL 并发：两个事务竞争同一批次只允许一个成功；
 * 失败事务无负库存、无部分流水、无履约接入；重复幂等键不重复领用；
 * 来源唯一约束拒绝同一来源重复入账。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryConcurrencyTest {

    private static final String USERNAME = "inventory-concurrency-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    ObjectMapper objectMapper;

    private Cookie sessionCookie;
    private long productId;
    private long orderId;
    private long orderItemId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES ('TI0004', 'TST-并发商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TI0004'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TC0010', 'TST-并发客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TC0010'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('T900002', ?, 'TST-并发客户', 'CONFIRMED', '2026-09-24', '收货人', '13800000000',
                    '华东', '地址', 60.0000, 0.0000, 0.0000, 60.0000, 36.0000, 0.0000, 36.0000, 24.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = 'T900002'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TI0004', 'TST-并发商品', 6, 0, 10.0000, 60.0000, 6.0000, 36.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    version, created_at, updated_at)
                VALUES (?, ?, 6, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testBatches = "SELECT id FROM inventory_batches WHERE product_no = 'TI0004'";
        jdbcTemplate.update("DELETE FROM inventory_allocation_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_movement_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE product_no = 'TI0004'");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE reverses_movement_id IS NOT NULL "
                + "AND id NOT IN (SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE id NOT IN "
                + "(SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_allocations WHERE id NOT IN "
                + "(SELECT DISTINCT allocation_id FROM inventory_allocation_lines)");
        var testOrder = "SELECT id FROM orders WHERE order_no = 'T900002'";
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'T900002'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TC0010'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TI0004'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void concurrentAllocationOnSameBatchAllowsSingleWinner() throws Exception {
        var batch = opening(10);
        var movementBefore = count("inventory_movements");

        Callable<Integer> allocateSix = () -> mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(batch, 6)))
                .andReturn().getResponse().getStatus();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = pool.invokeAll(List.of(allocateSix, allocateSix), 30, TimeUnit.SECONDS);
            var statuses = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    return -1;
                }
            }).toList();
            assertThat(statuses.stream().filter(status -> status == 201).count())
                    .as("两个事务竞争同一批次只允许一个成功，状态=" + statuses).isEqualTo(1);
            assertThat(statuses.stream().filter(status -> status == 409).count())
                    .as("失败事务返回库存不足").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // 失败事务无负库存、无部分流水、无履约接入
        assertThat(quantityOf(batch)).isEqualTo(4);
        assertThat(count("inventory_movements")).isEqualTo(movementBefore + 1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inventory_movement_lines WHERE batch_id = ? AND direction = 'OUT'
                """, Integer.class, batch)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fulfillment_entries WHERE order_item_id = ?
                    AND entry_type = 'INVENTORY_ALLOCATION'
                """, Integer.class, orderItemId)).isEqualTo(1);
        // 接入节点是 SHIPPABLE：投影落在最终可发货列
        assertThat(jdbcTemplate.queryForObject(
                "SELECT shippable_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM inventory_movement_lines WHERE batch_id = ?
                """, Integer.class, batch)).as("批次数量必须等于有效流水行汇总").isEqualTo(4);
    }

    @Test
    void repeatIdempotencyKeyDoesNotAllocateTwice() throws Exception {
        var batch = opening(10);
        var idempotencyKey = key();

        var first = mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(batch, 4)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var second = mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(batch, 4)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).path("data").path("id").asLong())
                .isEqualTo(objectMapper.readTree(first).path("data").path("id").asLong());
        assertThat(quantityOf(batch)).as("重复幂等键不得二次扣减").isEqualTo(6);
        assertThat(count("inventory_allocations")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fulfillment_entries WHERE order_item_id = ?
                    AND entry_type = 'INVENTORY_ALLOCATION'
                """, Integer.class, orderItemId)).isEqualTo(1);
    }

    @Test
    void sourceUniquenessRejectsDuplicateFulfillmentEntry() throws Exception {
        var batch = opening(10);
        mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(batch, 4)))
                .andExpect(status().isCreated());

        // 同一来源（流水 + 明细 + 节点 + 方向）不得重复入账
        var source = jdbcTemplate.queryForMap("""
                SELECT source_type, source_id, source_line_id, node, direction
                FROM fulfillment_entries WHERE order_item_id = ? AND entry_type = 'INVENTORY_ALLOCATION'
                """, orderItemId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'INVENTORY_ALLOCATION', ?, ?, 4, ?, ?, ?, '2026-09-24',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId, source.get("node"), source.get("direction"),
                source.get("source_type"), source.get("source_id"), source.get("source_line_id")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("uk_fulfillment_entries_source");
    }

    // ---------- 工具 ----------

    private long opening(int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/inventory/batches")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"node":"PACKING_BAG","seamState":"NONE","quantity":%d,
                                 "inventoryDate":"2026-09-24"}
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String body(long batchId, int quantity) {
        return """
                {"orderId":%d,"reason":"并发领用","lines":[{"batchId":%d,"orderItemId":%d,"quantity":%d,
                 "targetNode":"SHIPPABLE"}]}
                """.formatted(orderId, batchId, orderItemId, quantity);
    }

    private int quantityOf(long batchId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_batches WHERE id = ?", Integer.class, batchId);
        return value == null ? 0 : value;
    }

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
