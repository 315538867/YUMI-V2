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
 * 任务 7.8（并发部分）：并发关闭只生效一次、并发收款不丢更新。
 * 锁定顺序：订单行 → 履约投影行（按 id 升序）→ 款项投影行，见施工文档 §8。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementConcurrencyTest {

    private static final String USERNAME = "settlement-concurrency-admin";
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
                    sale_price, weight_g, total_cost, version, created_at, updated_at)
                VALUES ('TSN001', 'TST-结清并发商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TSN001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TSN001', 'TST-结清并发客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TSN001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TSN0001', ?, 'TST-结清并发客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TSN0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TSN001', 'TST-结清并发商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        // 已全部发货、应收 100：满足关闭的履约条件
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    shippable_quantity, shipped_quantity, version, created_at, updated_at)
                VALUES (?, ?, 10, 0, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TSN0001'";
        jdbcTemplate.update("DELETE FROM order_settlement_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM refunds WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM payments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TSN0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TSN001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TSN001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void concurrentCloseAppliesOnce() throws Exception {
        pay("100.0000").andExpect(status().isOk());

        Callable<Integer> close = this::closeStatus;
        var statuses = runConcurrently(close, close);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("CLOSED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT closed_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class, orderId)).isTrue();
    }

    @Test
    void concurrentPaymentsDoNotLoseUpdates() throws Exception {
        Callable<Integer> payForty = () -> payStatus("40.0000");
        var statuses = runConcurrently(payForty, payForty);

        assertThat(statuses).containsExactly(200, 200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE order_id = ?", Integer.class, orderId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_amount FROM order_settlement_balances WHERE order_id = ?", String.class, orderId))
                .as("两次并发收款都进入投影，不丢更新").isEqualTo("80.0000");
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions pay(String amount) throws Exception {
        return mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":"%s","businessDate":"2026-09-25","method":"微信"}
                        """.formatted(amount)));
    }

    private int payStatus(String amount) {
        try {
            return mockMvc.perform(post("/api/orders/" + orderId + "/payments")
                            .cookie(sessionCookie).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount":"%s","businessDate":"2026-09-25","method":"微信"}
                                    """.formatted(amount)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private int closeStatus() {
        try {
            return mockMvc.perform(post("/api/orders/" + orderId + "/close")
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

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
