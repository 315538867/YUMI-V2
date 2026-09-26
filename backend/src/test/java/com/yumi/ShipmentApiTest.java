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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 6.2–6.7：发货草稿、确认上限、快照与来源追溯、物流修改、作废、已关闭订单等量更正。
 * 口径见 `domain-and-quantity-model.md` §9 与施工文档 §4。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentApiTest {

    private static final String USERNAME = "shipment-admin";
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
                VALUES ('TSA001', 'TST-发货商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TSA001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TSA001', 'TST-发货客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TSA001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TSA0001', ?, 'TST-发货客户', 'CONFIRMED', '2026-09-25', '张三', '13800000000',
                    '华东', '上海市浦东新区示例路 1 号', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000,
                    60.0000, 40.0000, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TSA0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TSA001', 'TST-发货商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    shippable_quantity, version, created_at, updated_at)
                VALUES (?, ?, 10, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        // 可发货来源事实（生产合格），供发货来源追溯
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'PRODUCTION_QUALIFIED', 'SHIPPABLE', 'IN', 10, 'PRODUCTION', 1, 0,
                    '2026-09-25', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TSA0001'";
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
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TSA0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TSA001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TSA001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void draftDoesNotAffectQuantitiesAndConfirmConsumesShippable() throws Exception {
        int factsBefore = count("fulfillment_entries");
        var draft = createDraft(4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.shipmentNo").value(org.hamcrest.Matchers.startsWith("SH")))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].quantity").value(4))
                .andExpect(jsonPath("$.data.items[0].recipientName").value("张三"))
                .andExpect(jsonPath("$.data.items[0].cumulativeShippedQuantity").doesNotExist())
                .andReturn();
        long shipmentId = dataId(draft);

        // 草稿不影响可发货/累计发货，也不写事实
        assertThat(shippable()).as("草稿不占用可发货").isEqualTo(10);
        assertThat(shipped()).isZero();
        assertThat(count("fulfillment_entries")).isEqualTo(factsBefore);

        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items[0].cumulativeShippedQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].undeliveredQuantity").value(6))
                .andExpect(jsonPath("$.data.items[0].sourceLinks.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].sourceLinks[0].sourceType").value("PRODUCTION_QUALIFIED"))
                .andExpect(jsonPath("$.data.items[0].sourceLinks[0].quantity").value(4));

        assertThat(shippable()).isEqualTo(6);
        assertThat(shipped()).isEqualTo(4);
        var entry = jdbcTemplate.queryForMap("""
                SELECT entry_type, node, direction, quantity FROM fulfillment_entries
                WHERE order_item_id = ? AND entry_type = 'SHIPMENT_CONSUME'
                """, orderItemId);
        assertThat(entry.get("node")).isEqualTo("SHIPPABLE");
        assertThat(entry.get("direction")).isEqualTo("OUT");
        assertThat(((Number) entry.get("quantity")).intValue()).isEqualTo(4);
        // 发货不得再次扣减原库存
        assertThat(count("inventory_movements")).isZero();

        mockMvc.perform(get("/api/orders/" + orderId + "/shipments").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void rejectsOverAvailableAndOverDemand() throws Exception {
        long overAvailable = dataId(createDraft(11).andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + overAvailable + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_EXCEEDS_AVAILABLE"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='items[0].quantity')]").isNotEmpty());
        assertThat(shippable()).isEqualTo(10);
        assertThat(shipped()).isZero();

        // 累计发货 8 后，再发 4 会超过当前有效订购 10
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET shipped_quantity = 8, "
                + "shippable_quantity = 10 WHERE order_item_id = ?", orderItemId);
        long overDemand = dataId(createDraft(4).andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + overDemand + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_EXCEEDS_DEMAND"));
        assertThat(shipped()).as("整批不生效").isEqualTo(8);
    }

    @Test
    void voidRestoresQuantitiesAndKeepsConfirmationSnapshot() throws Exception {
        long shipmentId = dataId(createDraft(4).andExpect(status().isCreated()).andReturn());
        confirm(shipmentId).andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/void")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());

        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/void")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"客户取消本批\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"))
                .andExpect(jsonPath("$.data.voidReason").value("客户取消本批"))
                // 原确认快照保留
                .andExpect(jsonPath("$.data.items[0].cumulativeShippedQuantity").value(4));
        assertThat(shippable()).isEqualTo(10);
        assertThat(shipped()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fulfillment_entries
                WHERE order_item_id = ? AND entry_type = 'SHIPMENT_VOID' AND direction = 'IN'
                """, Integer.class, orderItemId)).isEqualTo(1);

        // 作废批次不可再次确认
        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CONFIRMABLE"));
    }

    /**
     * 回归（人工验收自测发现）：作废会减少有效已发数量，使既有售后受理量失去依据，
     * 因此被售后占用的批次必须按 `SHIPMENT_AFTER_SALES_LINKED` 拒绝（该错误码此前只声明未使用）。
     */
    @Test
    void voidRejectedWhenShipmentOccupiedByAfterSales() throws Exception {
        long shipmentId = dataId(createDraft(4).andExpect(status().isCreated()).andReturn());
        confirm(shipmentId).andExpect(status().isOk());
        long shipmentItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = ?", Long.class, shipmentId);

        jdbcTemplate.update("""
                INSERT INTO after_sales_cases (case_no, order_id, case_type, status, problem, version,
                    created_at, updated_at)
                VALUES ('AS880001', ?, 'REPLACEMENT', 'OPEN', '验收-占用用例', 0, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, orderId);
        long caseId = jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_cases WHERE case_no = 'AS880001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO after_sales_items (case_id, order_id, order_item_id, shipment_item_id, product_no,
                    product_name, accepted_quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TSA001', 'TST-发货商品', 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, caseId, orderId, orderItemId, shipmentItemId);

        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/void")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"验收-尝试作废被售后占用的批次\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPMENT_AFTER_SALES_LINKED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='shipmentId')]").isNotEmpty());
        // 批次保持已确认、累计发货不变
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo("CONFIRMED");
        assertThat(shipped()).isEqualTo(4);

        // 清理本用例自造的售后数据
        jdbcTemplate.update("DELETE FROM after_sales_items WHERE case_id = ?", caseId);
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE id = ?", caseId);
    }

    @Test
    void logisticsChangeKeepsQuantitiesAndRecordsHistory() throws Exception {
        long shipmentId = dataId(createDraft(3).andExpect(status().isCreated()).andReturn());
        confirm(shipmentId).andExpect(status().isOk());

        mockMvc.perform(patch("/api/orders/" + orderId + "/shipments/" + shipmentId + "/logistics")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"顺丰\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());

        mockMvc.perform(patch("/api/orders/" + orderId + "/shipments/" + shipmentId + "/logistics")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carrier":"顺丰","trackingNo":"SF123456","freight":"12.5000",
                                 "logisticsNote":"改顺丰","reason":"客户要求顺丰"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentCarrier").value("顺丰"))
                .andExpect(jsonPath("$.data.currentTrackingNo").value("SF123456"))
                // 原始物流快照保留不变，当前有效物流值随修改更新
                .andExpect(jsonPath("$.data.carrier").value("中通"))
                .andExpect(jsonPath("$.data.logisticsChanges.length()").value(1))
                .andExpect(jsonPath("$.data.logisticsChanges[0].afterCarrier").value("顺丰"))
                .andExpect(jsonPath("$.data.logisticsChanges[0].reason").value("客户要求顺丰"));
        // 物流修改不影响数量
        assertThat(shipped()).isEqualTo(3);
        assertThat(shippable()).isEqualTo(7);
    }

    @Test
    void correctionOnlyForClosedOrderAndKeepsDeliveredQuantity() throws Exception {
        long shipmentId = dataId(createDraft(4).andExpect(status().isCreated()).andReturn());
        confirm(shipmentId).andExpect(status().isOk());
        int shippableAfterConfirm = shippable();
        int shippedAfterConfirm = shipped();

        // 未关闭订单只能作废，不能更正
        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/corrections")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"录错\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));

        jdbcTemplate.update("UPDATE orders SET status = 'CLOSED' WHERE id = ?", orderId);
        var corrected = mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/corrections")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"收货信息录错\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(4))
                .andExpect(jsonPath("$.data.replacesShipmentId").value(shipmentId))
                .andReturn();
        long replacementId = dataId(corrected);
        assertThat(replacementId).isNotEqualTo(shipmentId);

        // 等量更正：交付数量不下降，原批次失效，可发货/累计发货回到确认后的水平
        assertThat(shipped()).isEqualTo(shippedAfterConfirm);
        assertThat(shippable()).isEqualTo(shippableAfterConfirm);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo("VOIDED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM shipment_corrections WHERE original_shipment_id = ? AND replacement_shipment_id = ?
                """, Integer.class, shipmentId, replacementId)).isEqualTo(1);

        // 一个原批次最多一次等量更正
        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/corrections")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"再更正\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_DUPLICATE"));

        // 已关闭订单不得直接作废
        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + replacementId + "/void")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"想作废\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CLOSED_REQUIRES_CORRECTION"));
    }

    @Test
    void rejectsDraftOnDraftOrderAndForeignItem() throws Exception {
        jdbcTemplate.update("UPDATE orders SET status = 'DRAFT' WHERE id = ?", orderId);
        createDraft(4)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));

        jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", orderId);
        mockMvc.perform(post("/api/orders/" + orderId + "/shipments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shipmentDate\":\"2026-09-25\",\"items\":[{\"orderItemId\":999999,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='items[0].orderItemId')]").isNotEmpty());
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions createDraft(int quantity) throws Exception {
        return mockMvc.perform(post("/api/orders/" + orderId + "/shipments")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"shipmentDate":"2026-09-25","carrier":"中通","trackingNo":"ZT001","freight":"8.0000",
                         "items":[{"orderItemId":%d,"quantity":%d}]}
                        """.formatted(orderItemId, quantity)));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(long shipmentId) throws Exception {
        return mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/confirm")
                .cookie(sessionCookie).header("Idempotency-Key", key()));
    }

    private long dataId(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("id").asLong();
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

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
