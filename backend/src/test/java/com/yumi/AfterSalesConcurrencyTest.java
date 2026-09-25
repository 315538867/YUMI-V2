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
 * 任务 8.11（并发部分）：两个并发补发发货只能有一个成功（售后可补发上限）。
 * 锁定顺序：售后明细行 → 补发批次行，见施工文档 §8。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AfterSalesConcurrencyTest {

    private static final String USERNAME = "after-sales-concurrency-admin";
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
    private long caseId;
    private long itemId;

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
                VALUES ('TAC001', 'TST-售后并发商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TAC001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TAC001', 'TST-售后并发客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TAC001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TAC0001', ?, 'TST-售后并发客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TAC0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TAC001', 'TST-售后并发商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    shippable_quantity, shipped_quantity, version, created_at, updated_at)
                VALUES (?, ?, 10, 6, 4, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, freight, current_freight,
                    created_at, updated_at)
                VALUES ('SH980001', ?, 'CONFIRMED', '2026-09-25', 0.0000, 0.0000, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, orderId);
        long shipmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = 'SH980001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 4, 'TAC001', 'TST-售后并发商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, shipmentId, orderItemId);
        long shipmentItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = ?", Long.class, shipmentId);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
        // 售后单：受理 4、补发需求 4
        var created = mockMvc.perform(post("/api/orders/" + orderId + "/after-sales")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseType":"REPLACEMENT","problem":"客户反馈破损",
                                 "items":[{"shipmentItemId":%d,"acceptedQuantity":4,"returnedQuantity":3,
                                 "replacementRequiredQuantity":4}]}
                                """.formatted(shipmentItemId)))
                .andExpect(status().isCreated())
                .andReturn();
        caseId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_items WHERE case_id = ?", Long.class, caseId);
        // 可补发 4（来源事实：库存接入）
        jdbcTemplate.update("""
                INSERT INTO after_sales_fulfillment_entries (after_sales_item_id, entry_type, direction,
                    quantity, source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, 'INVENTORY_INFLOW', 'IN', 4, 'INVENTORY', 1, 0, '2026-09-25', UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, itemId);
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TAC0001'";
        var cases = "SELECT id FROM after_sales_cases WHERE order_id IN (" + testOrders + ")";
        var items = "SELECT id FROM after_sales_items WHERE case_id IN (" + cases + ")";
        var shipments = "SELECT id FROM shipments WHERE order_id IN (" + testOrders + ")";
        jdbcTemplate.update("DELETE FROM after_sales_corrections WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_fulfillment_entries WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_shipment_links WHERE after_sales_item_id IN (" + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_return_verifications WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_items WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM shipment_source_links WHERE shipment_item_id IN "
                + "(SELECT id FROM shipment_items WHERE shipment_id IN (" + shipments + "))");
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TAC0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TAC001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TAC001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void concurrentReplacementShipmentsAllowOnlyOne() throws Exception {
        Callable<Integer> attempt = () -> {
            try {
                var draft = mockMvc.perform(post("/api/after-sales/" + caseId + "/replacement-shipments")
                                .cookie(sessionCookie).header("Idempotency-Key", key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"items":[{"afterSalesItemId":%d,"quantity":4}]}
                                        """.formatted(itemId)))
                        .andReturn();
                if (draft.getResponse().getStatus() != 201) {
                    return draft.getResponse().getStatus();
                }
                long shipmentId = latestShipmentId();
                return mockMvc.perform(post("/api/after-sales/" + caseId + "/replacement-shipments/"
                                + shipmentId + "/confirm")
                                .cookie(sessionCookie).header("Idempotency-Key", key()))
                        .andReturn().getResponse().getStatus();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        };
        var statuses = runConcurrently(attempt, attempt);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(shippedQuantity()).as("已补发只增加一次").isEqualTo(4);
        assertThat(availableQuantity()).as("可补发只被消耗一次").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM after_sales_fulfillment_entries
                WHERE after_sales_item_id = ? AND entry_type = 'REPLACEMENT_CONSUME'
                """, Integer.class, itemId)).isEqualTo(1);
    }

    // ---------- 工具 ----------

    private long latestShipmentId() {
        return jdbcTemplate.queryForObject("""
                SELECT shipment_id FROM after_sales_shipment_links WHERE after_sales_item_id = ?
                ORDER BY id DESC LIMIT 1
                """, Long.class, itemId);
    }

    private int shippedQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM after_sales_fulfillment_entries
                WHERE after_sales_item_id = ? AND entry_type = 'REPLACEMENT_CONSUME' AND direction = 'OUT'
                """, Integer.class, itemId);
        return value == null ? 0 : value;
    }

    private int availableQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM after_sales_fulfillment_entries WHERE after_sales_item_id = ?
                """, Integer.class, itemId);
        return value == null ? 0 : value;
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
