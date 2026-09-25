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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 4.6–4.10：多批次兼容领用、接入矩阵守卫、库存不足回滚、领用取消（反向事实）与流水冲销。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAllocationTest {

    private static final String USERNAME = "inventory-alloc-admin";
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
                VALUES ('TI0003', 'TST-领用商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TI0003'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TC0009', 'TST-领用客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TC0009'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('T900001', ?, 'TST-领用客户', 'CONFIRMED', '2026-09-24', '收货人', '13800000000',
                    '华东', '地址', 200.0000, 0.0000, 0.0000, 200.0000, 120.0000, 0.0000, 120.0000, 80.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = 'T900001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TI0003', 'TST-领用商品', 20, 0, 10.0000, 200.0000, 6.0000, 120.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    version, created_at, updated_at)
                VALUES (?, ?, 20, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
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
        var testBatches = "SELECT id FROM inventory_batches WHERE product_no = 'TI0003'";
        // 计划行同时引用批次与订单明细，必须最先清理，否则后面的批次/明细删除会被外键挡住
        jdbcTemplate.update("DELETE FROM order_inventory_plan_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_allocation_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_movement_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE product_no = 'TI0003'");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE reverses_movement_id IS NOT NULL "
                + "AND id NOT IN (SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE id NOT IN "
                + "(SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_allocations WHERE id NOT IN "
                + "(SELECT DISTINCT allocation_id FROM inventory_allocation_lines)");
        // 按订单号清理：setup 阶段本类字段尚未赋值
        var testOrder = "SELECT id FROM orders WHERE order_no = 'T900001'";
        jdbcTemplate.update("DELETE FROM order_item_snapshots WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_confirmation_snapshots WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'T900001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TC0009'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TI0003'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void allocatesMultipleBatchesAndRegistersFulfillment() throws Exception {
        var first = opening("MAKING", "NONE", 10);
        var second = opening("MAKING", "NONE", 4);

        var allocation = mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":%d,"reason":"客户急需","lines":[
                                  {"batchId":%d,"orderItemId":%d,"quantity":8,"targetNode":"PACKING_BAG"},
                                  {"batchId":%d,"orderItemId":%d,"quantity":4,"targetNode":"PACKING_BAG"}]}
                                """.formatted(orderId, first, orderItemId, second, orderItemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].quantityBefore").value(10))
                .andExpect(jsonPath("$.data.lines[0].quantityAfter").value(2))
                .andExpect(jsonPath("$.data.lines[1].quantityBefore").value(4))
                .andExpect(jsonPath("$.data.lines[1].quantityAfter").value(0))
                .andReturn();
        var data = objectMapper.readTree(allocation.getResponse().getContentAsString()).path("data");

        // 出库流水：一个 ALLOCATION 业务头 + 两条 OUT 行
        var movement = jdbcTemplate.queryForMap("""
                SELECT m.movement_no, m.movement_type, l.direction, l.quantity
                FROM inventory_movements m JOIN inventory_movement_lines l ON l.movement_id = m.id
                WHERE m.movement_type = 'ALLOCATION' AND l.batch_id = ? ORDER BY l.id LIMIT 1
                """, first);
        assertThat(String.valueOf(movement.get("movement_no"))).matches("IM\\d{6}");
        assertThat(movement.get("direction")).isEqualTo("OUT");
        assertThat(((Number) movement.get("quantity")).intValue()).isEqualTo(8);

        // 批次扣减
        assertThat(quantityOf(first)).isEqualTo(2);
        assertThat(quantityOf(second)).isZero();

        // 履约接入：INVENTORY_ALLOCATION / PACKING_BAG / IN，投影同步
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fulfillment_entries WHERE order_item_id = ?
                    AND entry_type = 'INVENTORY_ALLOCATION' AND node = 'PACKING_BAG' AND direction = 'IN'
                """, Integer.class, orderItemId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT packing_inflow FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId)).isEqualTo(12);
        assertThat(data.path("lines").get(0).path("orderNo").asText()).isEqualTo("T900001");
    }

    @Test
    void rejectsIncompatibleTargetDraftOrderAndInsufficientStock() throws Exception {
        var making = opening("MAKING", "NONE", 10);
        var factsBefore = count("fulfillment_entries");

        // 接入矩阵：制作合格不能直接接入可发货
        mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":%d,"lines":[{"batchId":%d,"orderItemId":%d,"quantity":5,
                                 "targetNode":"SHIPPABLE"}]}
                                """.formatted(orderId, making, orderItemId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='lines[0].targetNode')]").isNotEmpty());

        // 库存不足：返回每批缺口并整笔回滚
        mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":%d,"lines":[{"batchId":%d,"orderItemId":%d,"quantity":11,
                                 "targetNode":"PACKING_BAG"}]}
                                """.formatted(orderId, making, orderItemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='lines[0].quantity')]").isNotEmpty());
        assertThat(quantityOf(making)).as("失败事务不得扣减库存").isEqualTo(10);
        assertThat(count("inventory_allocations")).isZero();
        assertThat(count("fulfillment_entries")).isEqualTo(factsBefore);

        // 草稿订单不可领用
        jdbcTemplate.update("UPDATE orders SET status = 'DRAFT' WHERE id = ?", orderId);
        mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":%d,"lines":[{"batchId":%d,"orderItemId":%d,"quantity":1,
                                 "targetNode":"PACKING_BAG"}]}
                                """.formatted(orderId, making, orderItemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_EDITABLE"));
        jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", orderId);
    }

    @Test
    void cancelsUnconsumedAllocationWithReverseFacts() throws Exception {
        var batch = opening("PACKING_BAG", "NONE", 10);
        var allocationId = allocate(batch, 6, "SHIPPABLE");

        var cancelled = mockMvc.perform(post("/api/inventory-allocations/" + allocationId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"客户取消\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("客户取消"))
                .andReturn();
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);

        // 反向入库恢复批次；原领用流水仍保留
        assertThat(quantityOf(batch)).isEqualTo(10);
        var reversal = jdbcTemplate.queryForMap("""
                SELECT m.movement_type, m.reverses_movement_id, l.direction, l.quantity
                FROM inventory_movements m JOIN inventory_movement_lines l ON l.movement_id = m.id
                WHERE m.movement_type = 'ALLOCATION_CANCEL'
                """);
        assertThat(reversal.get("direction")).isEqualTo("IN");
        assertThat(((Number) reversal.get("quantity")).intValue()).isEqualTo(6);
        assertThat(reversal.get("reverses_movement_id")).isNotNull();
        assertThat(count("inventory_movements")).isEqualTo(3);

        // 反向履约事实 + 投影回退
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fulfillment_entries WHERE order_item_id = ?
                    AND entry_type = 'INVENTORY_ALLOCATION' AND direction = 'OUT'
                """, Integer.class, orderItemId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT shippable_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId)).isZero();

        // 重复取消被拒
        mockMvc.perform(post("/api/inventory-allocations/" + allocationId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"再取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CANNOT_CANCEL"));
    }

    @Test
    void rejectsCancelWhenAllocationAlreadyConsumed() throws Exception {
        var batch = opening("PACKING_BAG", "NONE", 10);
        var allocationId = allocate(batch, 6, "SEAM_CUTTING");
        // 模拟阶段五：该明细的领用接入已被生产核验消费
        jdbcTemplate.update("""
                INSERT INTO fulfillment_entries (order_id, order_item_id, entry_type, node, direction, quantity,
                    source_type, source_id, source_line_id, business_date, created_at, updated_at)
                VALUES (?, ?, 'PRODUCTION_QUALIFIED', 'SEAM_CUTTING', 'IN', 6, 'PRODUCTION', 1, 0,
                    '2026-09-24', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);

        mockMvc.perform(post("/api/inventory-allocations/" + allocationId + "/cancel")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"尝试取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CANNOT_CANCEL"));
        assertThat(quantityOf(batch)).as("拒绝取消不得回补库存").isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM inventory_allocations WHERE id = ?", String.class, allocationId))
                .isEqualTo("CONFIRMED");
    }

    @Test
    void reversesUnconsumedMovementOnlyOnce() throws Exception {
        var batch = opening("MAKING", "NONE", 10);
        var movementId = jdbcTemplate.queryForObject("""
                SELECT m.id FROM inventory_movements m JOIN inventory_movement_lines l ON l.movement_id = m.id
                WHERE l.batch_id = ? AND m.movement_type = 'OPENING'
                """, Long.class, batch);

        mockMvc.perform(post("/api/inventory/movements/" + movementId + "/reverse")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"录错数量\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.movementType").value("REVERSAL"))
                .andExpect(jsonPath("$.data.reversesMovementId").value(movementId));
        assertThat(quantityOf(batch)).isZero();

        // 已冲销不可重复冲销
        mockMvc.perform(post("/api/inventory/movements/" + movementId + "/reverse")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"再冲销\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CANNOT_CANCEL"));
        assertThat(count("inventory_movements")).isEqualTo(2);
    }

    @Test
    void confirmRevalidatesPersistedDraftInventoryPlanAndRequiresExplicitTransfer() throws Exception {
        var batch = opening("PACKING_BAG", "NONE", 5);
        jdbcTemplate.update("UPDATE orders SET status = 'DRAFT' WHERE id = ?", orderId);
        // 确认会自行建立投影行，先清掉 setup 为领用测试预插的那一条
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_item_id = ?", orderItemId);

        // 计划持久化在草稿上（任务 4.8）：8 件 > 批次 5 件
        mockMvc.perform(patch("/api/orders/" + orderId)
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"inventoryPlan\":[{\"lineNo\":1,\"batchId\":" + batch
                                + ",\"quantity\":8}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryPlan.length()").value(1))
                .andExpect(jsonPath("$.data.inventoryPlan[0].batchNo").isNotEmpty())
                .andExpect(jsonPath("$.data.inventoryPlan[0].batchQuantity").value(5));
        assertThat(quantityOf(batch)).as("保存计划不得占用或扣减库存").isEqualTo(5);

        // 确认重验：缺口未明确转生产 → 409 且整笔回滚
        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='inventoryPlan')]").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("DRAFT");
        assertThat(quantityOf(batch)).as("重验不得占用或扣减库存").isEqualTo(5);

        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferShortageToProduction\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(quantityOf(batch)).isEqualTo(5);
        assertThat(count("inventory_allocations")).as("确认不创建领用事实").isZero();
    }

    // ---------- 工具 ----------

    private long opening(String node, String seamState, int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/inventory/batches")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"node":"%s","seamState":"%s","quantity":%d,
                                 "inventoryDate":"2026-09-24"}
                                """.formatted(productId, node, seamState, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long allocate(long batchId, int quantity, String targetNode) throws Exception {
        var created = mockMvc.perform(post("/api/inventory-allocations")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":%d,"reason":"领用","lines":[{"batchId":%d,"orderItemId":%d,
                                 "quantity":%d,"targetNode":"%s"}]}
                                """.formatted(orderId, batchId, orderItemId, quantity, targetNode)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
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
        return java.util.UUID.randomUUID().toString();
    }
}
