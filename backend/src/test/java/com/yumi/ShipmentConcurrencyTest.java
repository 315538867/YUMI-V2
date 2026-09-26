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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 6.9（并发部分）：两个并发发货只能有一个成功、同一批次重复确认只生效一次。
 * 其余场景（超量、整批回滚、领用后不二扣库存、无售后作废恢复、关闭后等量更正、物流修改不影响数量）
 * 见 `ShipmentApiTest`；「已被售后占用时拒绝作废/失效更正」依赖阶段八的售后表，随 8.x 接入后补测。
 * 锁定顺序：履约投影行（按 `order_item_id` 升序）→ 批次行，见施工文档 §8。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentConcurrencyTest {

    private static final String USERNAME = "shipment-concurrency-admin";
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
                VALUES ('TSC001', 'TST-并发发货商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TSC001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TSC001', 'TST-并发发货客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TSC001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TSC0001', ?, 'TST-并发发货客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TSC0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TSC001', 'TST-并发发货商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        // 可发货只有 6：两个各 4 件的批次里只有一个能确认
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    shippable_quantity, version, created_at, updated_at)
                VALUES (?, ?, 10, 6, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TSC0001'";
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
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TSC0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TSC001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TSC001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void twoConcurrentConfirmationsAllowOnlyOne() throws Exception {
        long first = createDraft(4);
        long second = createDraft(4);

        Callable<Integer> confirmFirst = () -> confirmStatus(first);
        Callable<Integer> confirmSecond = () -> confirmStatus(second);
        var statuses = runConcurrently(confirmFirst, confirmSecond);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(shippable()).as("可发货只被消耗一次").isEqualTo(2);
        assertThat(shipped()).as("累计发货只增加一次").isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fulfillment_entries
                WHERE order_item_id = ? AND entry_type = 'SHIPMENT_CONSUME'
                """, Integer.class, orderItemId)).isEqualTo(1);
    }

    @Test
    void concurrentConfirmOfSameShipmentAppliesOnce() throws Exception {
        long shipmentId = createDraft(4);

        Callable<Integer> confirm = () -> confirmStatus(shipmentId);
        var statuses = runConcurrently(confirm, confirm);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(shipped()).as("同一批次只生效一次").isEqualTo(4);
        assertThat(shippable()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_entries WHERE order_item_id = ? AND entry_type = 'SHIPMENT_CONSUME'",
                Integer.class, orderItemId)).isEqualTo(1);
    }

    // ---------- 工具 ----------

    private long createDraft(int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/orders/" + orderId + "/shipments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipmentDate":"2026-09-25","items":[{"orderItemId":%d,"quantity":%d}]}
                                """.formatted(orderItemId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private int confirmStatus(long shipmentId) {
        try {
            return mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/confirm")
                            .cookie(sessionCookie).header("Idempotency-Key", key()))
                    .andReturn().getResponse().getStatus();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private List<Integer> runConcurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = pool.invokeAll(List.of(first, second), 30, TimeUnit.SECONDS);
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            pool.shutdownNow();
        }
    }

    private int shippable() {
        return balance("shippable_quantity");
    }

    private int shipped() {
        return balance("shipped_quantity");
    }

    private int balance(String column) {
        var value = jdbcTemplate.queryForObject("SELECT " + column
                + " FROM order_item_fulfillment_balances WHERE order_item_id = ?", Integer.class, orderItemId);
        return value == null ? 0 : value;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
