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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 4.2–4.5 库存 API：批次/汇总/流水查询、期初入库、盘点调整与领用推荐（接入矩阵 + FIFO + 只推荐不占用）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryApiTest {

    private static final String USERNAME = "inventory-api-admin";
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

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, version, created_at, updated_at)
                VALUES ('TI0002', 'TST-库存商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TI0002'", Long.class);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        // 批次编号由序列生成（IB000001…），按商品清理本次测试造出的全部库存数据
        var testBatches = "SELECT id FROM inventory_batches WHERE product_no = 'TI0002'";
        jdbcTemplate.update("DELETE FROM inventory_allocation_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_movement_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE product_no = 'TI0002'");
        // 流水只追加：删掉已无流水行的业务头（本次测试产生的那些）
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE id NOT IN "
                + "(SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM inventory_allocations WHERE id NOT IN "
                + "(SELECT DISTINCT allocation_id FROM inventory_allocation_lines)");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TI0002'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    // ---------- 4.3 期初库存 ----------

    @Test
    void openingCreatesBatchAndInboundMovementWithoutForgingFacts() throws Exception {
        var ordersBefore = count("orders");
        var fulfillmentBefore = count("fulfillment_entries");
        var changeLogsBefore = count("master_data_change_logs");

        var batch = opening("MAKING", "NONE", 10);
        assertThat(batch.path("batchNo").asText()).matches("IB\\d{6}");
        assertThat(batch.path("quantity").asInt()).isEqualTo(10);
        assertThat(batch.path("node").asText()).isEqualTo("MAKING");
        assertThat(batch.path("seamState").asText()).isEqualTo("NONE");
        assertThat(batch.path("sourceType").asText()).isEqualTo("OPENING");

        // 入库流水：IM 编号、IN、变动前后数量
        var movement = jdbcTemplate.queryForMap("""
                SELECT m.movement_no, m.movement_type, l.direction, l.quantity, l.quantity_before,
                       l.quantity_after
                FROM inventory_movements m JOIN inventory_movement_lines l ON l.movement_id = m.id
                WHERE l.batch_id = ?
                """, batch.path("id").asLong());
        assertThat(String.valueOf(movement.get("movement_no"))).matches("IM\\d{6}");
        assertThat(movement.get("movement_type")).isEqualTo("OPENING");
        assertThat(movement.get("direction")).isEqualTo("IN");
        assertThat(((Number) movement.get("quantity")).intValue()).isEqualTo(10);
        assertThat(((Number) movement.get("quantity_before")).intValue()).isZero();
        assertThat(((Number) movement.get("quantity_after")).intValue()).isEqualTo(10);

        // 不伪造历史订单或生产/工资事实
        assertThat(count("orders")).isEqualTo(ordersBefore);
        assertThat(count("fulfillment_entries")).isEqualTo(fulfillmentBefore);
        assertThat(count("master_data_change_logs")).isEqualTo(changeLogsBefore);
    }

    @Test
    void rejectsInvalidOpening() throws Exception {
        expectValidation("""
                {"productId":%d,"node":"MAKING","seamState":"NONE","quantity":0,"inventoryDate":"2026-09-24"}
                """.formatted(productId), "quantity");
        expectValidation("""
                {"productId":%d,"node":"UNKNOWN","seamState":"NONE","quantity":1,"inventoryDate":"2026-09-24"}
                """.formatted(productId), "node");
        expectValidation("""
                {"productId":%d,"node":"MAKING","seamState":"DONE","quantity":1,"inventoryDate":"2026-09-24"}
                """.formatted(productId), "seamState");
        expectValidation("""
                {"productId":999999,"node":"MAKING","seamState":"NONE","quantity":1,"inventoryDate":"2026-09-24"}
                """, "productId");
        expectValidation("""
                {"productId":%d,"node":"MAKING","seamState":"NONE","quantity":1}
                """.formatted(productId), "inventoryDate");
        assertThat(count("inventory_batches")).isZero();
    }

    // ---------- 4.4 盘点调整 ----------

    @Test
    void adjustmentWritesSurplusAndLossMovements() throws Exception {
        var batchId = opening("PACKING_BAG", "NONE", 10).path("id").asLong();

        // 盘盈 2：IN 2，前后 10 → 12
        mockMvc.perform(post("/api/inventory/adjustments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":%d,\"actualQuantity\":12,\"reason\":\"复盘多出\"}".formatted(batchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(12));
        var surplus = jdbcTemplate.queryForMap("""
                SELECT m.movement_type, m.reason, l.direction, l.quantity, l.quantity_before, l.quantity_after
                FROM inventory_movements m JOIN inventory_movement_lines l ON l.movement_id = m.id
                WHERE l.batch_id = ? AND m.movement_type = 'ADJUSTMENT'
                """, batchId);
        assertThat(surplus.get("direction")).isEqualTo("IN");
        assertThat(((Number) surplus.get("quantity")).intValue()).isEqualTo(2);
        assertThat(((Number) surplus.get("quantity_before")).intValue()).isEqualTo(10);
        assertThat(((Number) surplus.get("quantity_after")).intValue()).isEqualTo(12);
        assertThat(surplus.get("reason")).isEqualTo("复盘多出");

        // 盘亏 4：OUT 4，前后 12 → 8
        mockMvc.perform(post("/api/inventory/adjustments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":%d,\"actualQuantity\":8,\"reason\":\"破损\"}".formatted(batchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(8));
        var loss = jdbcTemplate.queryForMap("""
                SELECT l.direction, l.quantity, l.quantity_before, l.quantity_after
                FROM inventory_movements m JOIN inventory_movement_lines l ON l.movement_id = m.id
                WHERE l.batch_id = ? AND m.movement_type = 'ADJUSTMENT' AND l.direction = 'OUT'
                """, batchId);
        assertThat(((Number) loss.get("quantity")).intValue()).isEqualTo(4);
        assertThat(((Number) loss.get("quantity_before")).intValue()).isEqualTo(12);
        assertThat(((Number) loss.get("quantity_after")).intValue()).isEqualTo(8);

        // 数量一致不写记录
        var movementsBefore = count("inventory_movements");
        mockMvc.perform(post("/api/inventory/adjustments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":%d,\"actualQuantity\":8,\"reason\":\"复核一致\"}".formatted(batchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(8));
        assertThat(count("inventory_movements")).isEqualTo(movementsBefore);

        // 批次当前数量等于有效流水行汇总
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM inventory_movement_lines WHERE batch_id = ?
                """, Integer.class, batchId)).isEqualTo(8);
    }

    @Test
    void rejectsAdjustmentWithoutReasonOrUnknownBatch() throws Exception {
        var batchId = opening("MAKING", "NONE", 5).path("id").asLong();
        mockMvc.perform(post("/api/inventory/adjustments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":%d,\"actualQuantity\":3}".formatted(batchId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());
        mockMvc.perform(post("/api/inventory/adjustments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":999999,\"actualQuantity\":3,\"reason\":\"不存在\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- 4.2 查询 ----------

    @Test
    void queriesBatchesSummaryAndMovements() throws Exception {
        opening("MAKING", "NONE", 10);
        opening("MAKING", "NONE", 4);
        opening("SEAM_CUTTING", "DONE", 6);

        mockMvc.perform(get("/api/inventory/batches").cookie(sessionCookie)
                        .param("node", "MAKING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // 汇总按 商品 + 工序 + 缝边状态：制作未缝边 14（2 批）、缝边剪袋已缝边 6（1 批）
        var summary = objectMapper.readTree(mockMvc.perform(get("/api/inventory/summary")
                        .cookie(sessionCookie).param("productId", String.valueOf(productId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(summary.size()).isEqualTo(2);
        assertThat(summary.get(0).path("node").asText()).isEqualTo("MAKING");
        assertThat(summary.get(0).path("quantity").asInt()).isEqualTo(14);
        assertThat(summary.get(0).path("batchCount").asInt()).isEqualTo(2);
        assertThat(summary.get(1).path("node").asText()).isEqualTo("SEAM_CUTTING");
        assertThat(summary.get(1).path("quantity").asInt()).isEqualTo(6);

        // 零库存默认隐藏，历史仍可查
        var emptyBatchId = opening("MAKING", "NONE", 3).path("id").asLong();
        mockMvc.perform(post("/api/inventory/adjustments")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":%d,\"actualQuantity\":0,\"reason\":\"清零\"}".formatted(emptyBatchId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory/batches").cookie(sessionCookie)
                        .param("node", "MAKING"))
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/api/inventory/batches").cookie(sessionCookie)
                        .param("node", "MAKING").param("includeEmpty", "true"))
                .andExpect(jsonPath("$.data.length()").value(3));

        // 流水查询：含变动前后数量与来源追溯
        var movements = objectMapper.readTree(mockMvc.perform(get("/api/inventory/movements")
                        .cookie(sessionCookie).param("batchId", String.valueOf(emptyBatchId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(movements.size()).isEqualTo(2);
        // 流水按 id 倒序：get(0) 是盘点（3→0），get(1) 是期初（0→3）
        assertThat(movements.get(0).path("movementType").asText()).isEqualTo("ADJUSTMENT");
        assertThat(movements.get(0).path("lines").get(0).path("quantityBefore").asInt()).isEqualTo(3);
        assertThat(movements.get(0).path("lines").get(0).path("quantityAfter").asInt()).isZero();
        assertThat(movements.get(1).path("movementType").asText()).isEqualTo("OPENING");
        assertThat(movements.get(1).path("lines").get(0).path("batchNo").asText()).startsWith("IB");
        assertThat(movements.get(1).path("lines").get(0).path("quantityBefore").asInt()).isZero();
        assertThat(movements.get(1).path("lines").get(0).path("quantityAfter").asInt()).isEqualTo(3);
    }

    // ---------- 4.5 领用推荐与接入矩阵 ----------

    @Test
    void recommendationsFollowAllocationMatrixAndFifo() throws Exception {
        var making = opening("MAKING", "NONE", 10).path("id").asLong();
        var packing = opening("PACKING_BAG", "NONE", 5).path("id").asLong();
        var seamed = opening("SEAM_CUTTING", "DONE", 7).path("id").asLong();
        opening("SHIPPABLE", "NONE", 2);

        // 制作合格 → 只能接入捏毛装袋
        var forPacking = recommend("PACKING_BAG");
        assertThat(forPacking.size()).isEqualTo(1);
        assertThat(forPacking.get(0).path("batchId").asLong()).isEqualTo(making);
        assertThat(forPacking.get(0).path("allowedTargets").get(0).asText()).isEqualTo("PACKING_BAG");

        // 捏毛装袋合格（未缝边）→ 不缝边可发货 或 缝边剪袋
        var forShipping = recommend("SHIPPABLE");
        assertThat(forShipping.size()).isEqualTo(2);
        assertThat(forShipping.get(0).path("batchId").asLong()).isEqualTo(packing);
        assertThat(forShipping.get(1).path("batchId").asLong()).isEqualTo(seamed);
        var forSeam = recommend("SEAM_CUTTING");
        assertThat(forSeam.size()).isEqualTo(1);
        assertThat(forSeam.get(0).path("batchId").asLong()).isEqualTo(packing);

        // 可发货是终态，不可再接入
        var forMaking = recommend("MAKING");
        assertThat(forMaking).isEmpty();

        // 只推荐不占用：数量与流水数量不变
        var movementsBefore = count("inventory_movements");
        mockMvc.perform(get("/api/inventory/recommendations").cookie(sessionCookie)
                        .param("productId", String.valueOf(productId)).param("targetNode", "SHIPPABLE"))
                .andExpect(status().isOk());
        assertThat(count("inventory_movements")).isEqualTo(movementsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM inventory_batches WHERE id = ?", Integer.class, packing)).isEqualTo(5);

        // 非法接入工序
        mockMvc.perform(get("/api/inventory/recommendations").cookie(sessionCookie)
                        .param("productId", String.valueOf(productId)).param("targetNode", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
    }

    // ---------- 工具 ----------

    private JsonNode opening(String node, String seamState, int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/inventory/batches")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"node":"%s","seamState":"%s","quantity":%d,
                                 "inventoryDate":"2026-09-24","note":"期初盘点"}
                                """.formatted(productId, node, seamState, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
    }

    private JsonNode recommend(String targetNode) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/inventory/recommendations")
                        .cookie(sessionCookie).param("productId", String.valueOf(productId))
                        .param("targetNode", targetNode))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private void expectValidation(String body, String field) throws Exception {
        mockMvc.perform(post("/api/inventory/batches")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='" + field + "')]").isNotEmpty());
    }

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
