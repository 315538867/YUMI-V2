package com.yumi;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 3.5 确认原子性：单事务快照与履约需求事实、校验失败整笔回滚、
 * 重复幂等键返回同一结果且不重复生成快照/来源、并发确认只保留一套快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderConfirmationTest {

    private static final String USERNAME = "order-confirm-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    com.yumi.orders.order.internal.OrderSnapshotRepository snapshotRepository;

    private Cookie sessionCookie;
    private long customerId;
    private long productId;
    private long seamTypeId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, contact, phone, default_recipient,
                    default_recipient_phone, default_region, default_address, version, created_at, updated_at)
                VALUES ('TC0003', 'TST-确认客户', '联系人', '13900000000', '默认收货人', '13900000001',
                    '华南', '默认收货地址', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TC0003'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO seam_types (name, cost_price, version, created_at, updated_at)
                VALUES ('TST-确认缝边种类', 1.2500, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        seamTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM seam_types WHERE name = 'TST-确认缝边种类'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, note, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, glue_grams, glue_cost, colorpaste_cost, material_cost,
                    product_labor_fee, packaging_labor_fee, box_labor_fee, labor_cost, other_cost,
                    seam_type_id, seam_type_name, seam_type_cost_price, seam_fee,
                    version, created_at, updated_at)
                VALUES ('TP0003', 'TST-确认商品', '商品说明', 'ACTIVE', (SELECT MIN(id) FROM star_levels),
                    '一星', 5, 25.0000, 100, 18.1200, 324, 3.2400, 6.4800, 9.7200, 5.0000, 2.0000, 0.5000,
                    7.5000, 0.9000, ?, 'TST-确认缝边种类', 1.2500, 2.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, seamTypeId);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TP0003'", Long.class);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TC0003')";
        jdbcTemplate.update("DELETE FROM order_change_items WHERE change_order_id IN "
                + "(SELECT id FROM order_change_orders WHERE order_id IN (" + testOrders + "))");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_change_orders", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TC0003')");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TP0003'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-确认缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TC0003'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void confirmsOrderWritingSnapshotsAndDemandFacts() throws Exception {
        var draft = createDraft();
        var orderId = draft.path("id").asLong();

        var confirmed = mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.receivableAmount").value("248.0000"))
                .andReturn();
        var data = objectMapper.readTree(confirmed.getResponse().getContentAsString()).path("data");
        var orderNo = data.path("orderNo").asText();

        // 订单级快照：客户、收货、金额、确认人/时间
        var snapshot = jdbcTemplate.queryForMap(
                "SELECT * FROM order_confirmation_snapshots WHERE order_id = ?", orderId);
        assertThat(snapshot.get("customer_no")).isEqualTo("TC0003");
        assertThat(snapshot.get("customer_name")).isEqualTo("TST-确认客户");
        assertThat(snapshot.get("recipient_name")).isEqualTo("默认收货人");
        assertThat(snapshot.get("region")).isEqualTo("华南");
        assertThat(String.valueOf(snapshot.get("goods_amount"))).isEqualTo("250.0000");
        assertThat(String.valueOf(snapshot.get("seam_amount"))).isEqualTo("8.0000");
        assertThat(String.valueOf(snapshot.get("receivable_amount"))).isEqualTo("248.0000");
        assertThat(String.valueOf(snapshot.get("cost_amount"))).isEqualTo("186.2000");
        assertThat(String.valueOf(snapshot.get("profit_amount"))).isEqualTo("61.8000");
        assertThat(snapshot.get("confirmed_by")).isEqualTo(USERNAME);
        assertThat(snapshot.get("confirmed_at")).isNotNull();

        // 明细级快照：商品识别、星级、成本组成、缝边参数与冻结流程
        var itemSnapshot = jdbcTemplate.queryForMap(
                "SELECT * FROM order_item_snapshots WHERE order_id = ?", orderId);
        assertThat(itemSnapshot.get("product_no")).isEqualTo("TP0003");
        assertThat(itemSnapshot.get("product_name")).isEqualTo("TST-确认商品");
        assertThat(itemSnapshot.get("product_note")).isEqualTo("商品说明");
        assertThat(itemSnapshot.get("star_name")).isEqualTo("一星");
        assertThat(((Number) itemSnapshot.get("star_std_minutes")).intValue()).isEqualTo(5);
        assertThat(((Number) itemSnapshot.get("quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) itemSnapshot.get("seam_quantity")).intValue()).isEqualTo(4);
        assertThat(String.valueOf(itemSnapshot.get("unit_cost"))).isEqualTo("18.1200");
        assertThat(String.valueOf(itemSnapshot.get("glue_cost"))).isEqualTo("3.2400");
        assertThat(String.valueOf(itemSnapshot.get("material_cost"))).isEqualTo("9.7200");
        assertThat(String.valueOf(itemSnapshot.get("labor_cost"))).isEqualTo("7.5000");
        assertThat(String.valueOf(itemSnapshot.get("total_cost"))).isEqualTo("18.1200");
        assertThat(itemSnapshot.get("flow")).isEqualTo("制作 → 捏毛装袋 → 缝边剪袋 → 可发货");

        // 履约事实与投影：一条需求事实 + 一条投影，节点与方向固定
        var entries = jdbcTemplate.queryForList(
                "SELECT entry_type, node, direction, quantity, source_type FROM fulfillment_entries "
                        + "WHERE order_id = ?", orderId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("entry_type")).isEqualTo("ORDER_DEMAND");
        assertThat(entries.get(0).get("node")).isEqualTo("SHIPPABLE");
        assertThat(entries.get(0).get("direction")).isEqualTo("IN");
        assertThat(((Number) entries.get(0).get("quantity")).intValue()).isEqualTo(10);
        assertThat(entries.get(0).get("source_type")).isEqualTo("ORDER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT required_quantity FROM order_item_fulfillment_balances WHERE order_id = ?",
                Integer.class, orderId)).isEqualTo(10);

        // 不自动建生产计划：不存在任何生产类履约事实
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_entries WHERE order_id = ? AND entry_type LIKE 'PRODUCTION%'",
                Integer.class, orderId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE order_no = ? AND status = 'CONFIRMED'",
                Integer.class, orderNo)).isEqualTo(1);
    }

    @Test
    void confirmationRollsBackWhenProductDisabled() throws Exception {
        var draft = createDraft();
        var orderId = draft.path("id").asLong();
        jdbcTemplate.update("UPDATE products SET status = 'DISABLED' WHERE id = ?", productId);

        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='items[0].productId')]").isNotEmpty());

        assertRolledBack(orderId);
    }

    @Test
    void confirmationRollsBackWhenAmountsInconsistent() throws Exception {
        var draft = createDraft();
        var orderId = draft.path("id").asLong();
        jdbcTemplate.update("UPDATE orders SET receivable_amount = 999.0000 WHERE id = ?", orderId);

        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='amounts')]").isNotEmpty());

        assertRolledBack(orderId);
    }

    @Test
    void confirmationRollsBackWhenDeliveryDateInvalid() throws Exception {
        var draft = createDraft();
        var orderId = draft.path("id").asLong();
        // 绕过保存校验直接改库，制造非法交期
        jdbcTemplate.update("UPDATE orders SET expected_delivery_date = '2026-09-01' WHERE id = ?", orderId);

        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='expectedDeliveryDate')]").isNotEmpty());

        assertRolledBack(orderId);
    }

    @Test
    void repeatIdempotencyKeyReturnsSameOrderWithoutDuplicateFacts() throws Exception {
        var draft = createDraft();
        var orderId = draft.path("id").asLong();
        var idempotencyKey = key();

        var first = mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var second = mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).path("data").path("orderNo").asText())
                .isEqualTo(objectMapper.readTree(first).path("data").path("orderNo").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_confirmation_snapshots WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_item_snapshots WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_entries WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
    }

    @Test
    void concurrentConfirmKeepsSingleSnapshotSet() throws Exception {
        var draft = createDraft();
        var orderId = draft.path("id").asLong();

        Callable<Integer> confirm = () -> mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key()))
                .andReturn().getResponse().getStatus();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(List.of(confirm, confirm), 30, TimeUnit.SECONDS);
            var statuses = results.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception failure) {
                    return -1;
                }
            }).toList();
            assertThat(statuses.stream().filter(status -> status == 200).count())
                    .as("并发确认只允许一次成功，状态=" + statuses).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_confirmation_snapshots WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_item_snapshots WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_entries WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void staleVersionGuardRejectsConfirmationWrite() throws Exception {
        var draft = createDraft();
        var orderId = draft.path("id").asLong();
        // 制造版本漂移：确认写入必须带版本条件，失配时更新 0 行（服务层据此抛 CONFLICT_VERSION）
        jdbcTemplate.update("UPDATE orders SET version = 5 WHERE id = ?", orderId);

        assertThat(snapshotRepository.markConfirmed(orderId, 0L, USERNAME, "req-stale"))
                .as("旧版本确认写入必须被拒绝").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("DRAFT");
        assertThat(snapshotRepository.orderSnapshotCount(orderId)).isZero();
    }

    // ---------- 工具 ----------

    private void assertRolledBack(long orderId) {
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .as("校验失败时订单必须仍是草稿").isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, orderId))
                .as("回滚不得递增版本").isZero();
        for (var table : new String[]{"order_confirmation_snapshots", "order_item_snapshots",
                "fulfillment_entries", "order_item_fulfillment_balances"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE order_id = ?", Integer.class, orderId))
                    .as(table + " 不得留下部分快照或事实").isZero();
        }
    }

    private JsonNode createDraft() throws Exception {
        var created = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"orderDate":"2026-09-24","discountAmount":"10.0000",
                                 "items":[{"productId":%d,"quantity":10,"seamQuantity":4}]}
                                """.formatted(customerId, productId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
