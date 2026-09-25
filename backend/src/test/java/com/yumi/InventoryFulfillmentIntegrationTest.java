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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 4.13 跨模块集成：库存领用时已扣原库存，随后发货只消耗订单可发货，
 * **库存流水数量不因发货第二次减少**。
 * 口径见 `inventory-management`「发货不得重复扣减原库存」与 `order-lifecycle`「订单必须维护可发货和发货上限」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryFulfillmentIntegrationTest {

    private static final String USERNAME = "inventory-shipment-integration-admin";
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
                    sale_price, weight_g, total_cost, version, created_at, updated_at)
                VALUES ('TIF001', 'TST-集成商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TIF001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TIF001', 'TST-集成客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TIF001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TIF0001', ?, 'TST-集成客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TIF0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TIF001', 'TST-集成商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    version, created_at, updated_at)
                VALUES (?, ?, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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
        var testBatches = "SELECT id FROM inventory_batches WHERE product_no = 'TIF001'";
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TIF0001'";
        var shipments = "SELECT id FROM shipments WHERE order_id IN (" + testOrders + ")";
        jdbcTemplate.update("DELETE FROM shipment_source_links WHERE shipment_item_id IN "
                + "(SELECT id FROM shipment_items WHERE shipment_id IN (" + shipments + "))");
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_inventory_plan_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_allocation_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_movement_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE product_no = 'TIF001'");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE reverses_movement_id IS NOT NULL "
                + "AND id NOT IN (SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE id NOT IN "
                + "(SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_allocations WHERE id NOT IN "
                + "(SELECT DISTINCT allocation_id FROM inventory_allocation_lines)");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TIF0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TIF001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TIF001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void allocationDeductsStockOnceAndShipmentConsumesShippableOnly() throws Exception {
        long batchId = openingBatch(10);

        // ① 库存领用：扣原库存一次，接入订单可发货
        mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":%d,"reason":"接入订单","lines":[
                                  {"batchId":%d,"orderItemId":%d,"quantity":6,"targetNode":"SHIPPABLE"}]}
                                """.formatted(orderId, batchId, orderItemId)))
                .andExpect(status().isCreated());
        assertThat(batchQuantity(batchId)).as("领用后批次扣减一次").isEqualTo(4);
        assertThat(movementLines(batchId)).as("领用产生一条库存出库流水行").isEqualTo(1);
        assertThat(shippableQuantity()).as("订单可发货 +6").isEqualTo(6);
        assertThat(shippedQuantity()).isZero();

        // ② 发货：只消耗订单可发货，不再扣原库存
        var draft = mockMvc.perform(post("/api/orders/" + orderId + "/shipments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shipmentDate":"2026-09-25","freight":0,"items":[
                                  {"orderItemId":%d,"quantity":6}]}
                                """.formatted(orderItemId)))
                .andExpect(status().isCreated())
                .andReturn();
        long shipmentId = dataId(draft);
        mockMvc.perform(post("/api/orders/" + orderId + "/shipments/" + shipmentId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        assertThat(batchQuantity(batchId)).as("发货不再第二次扣减库存").isEqualTo(4);
        assertThat(movementLines(batchId)).as("发货不产生新的库存流水行").isEqualTo(1);
        assertThat(shippableQuantity()).as("可发货被发货消耗").isZero();
        assertThat(shippedQuantity()).as("累计发货 6").isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inventory_movements WHERE movement_type = 'SHIPMENT'
                """, Integer.class)).as("发货不写库存流水").isZero();
    }

    // ---------- 工具 ----------

    private long openingBatch(int quantity) {
        // 缝边剪袋（已缝边）批次可接入可发货；可发货批次本身不是领用来源
        jdbcTemplate.update("""
                INSERT INTO inventory_batches (batch_no, product_id, product_no, product_name, source_type,
                    source_id, source_line_id, node, seam_state, quantity, inventory_date, created_at, updated_at)
                VALUES ('IB800001', ?, 'TIF001', 'TST-集成商品', 'OPENING', 1, 0, 'SEAM_CUTTING', 'DONE', ?,
                    '2026-09-25', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productId, quantity);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inventory_batches WHERE batch_no = 'IB800001'", Long.class);
    }

    private int batchQuantity(long batchId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_batches WHERE id = ?", Integer.class, batchId);
        return value == null ? 0 : value;
    }

    private int movementLines(long batchId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_movement_lines WHERE batch_id = ?", Integer.class, batchId);
        return value == null ? 0 : value;
    }

    private int shippableQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT shippable_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, Integer.class, orderItemId);
        return value == null ? 0 : value;
    }

    private int shippedQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT shipped_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, Integer.class, orderItemId);
        return value == null ? 0 : value;
    }

    private long dataId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private static String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
