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
 * 任务 4.8：草稿库存计划持久化、校验、整体替换，以及确认时按批次聚合重验。
 * 计划不占用库存（specs/inventory-management/spec.md「草稿库存计划不占用库存」）：
 * 保存与确认都不得改动 inventory_batches.quantity，也不得产生领用事实。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderInventoryPlanTest {

    private static final String USERNAME = "order-plan-admin";
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
    private long otherProductId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        productId = insertProduct("TP0009", "TST-计划商品");
        otherProductId = insertProduct("TP0010", "TST-计划商品二");
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TC0019', 'TST-计划客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_name = 'TST-计划客户'";
        var testBatches = "SELECT id FROM inventory_batches WHERE product_no IN ('TP0009', 'TP0010')";
        // 计划行同时引用 orders/order_items 与 inventory_batches，必须最先清理
        jdbcTemplate.update("DELETE FROM order_inventory_plan_lines WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM inventory_allocation_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_movement_lines WHERE batch_id IN (" + testBatches + ")");
        jdbcTemplate.update("DELETE FROM inventory_batches WHERE product_no IN ('TP0009', 'TP0010')");
        jdbcTemplate.update("DELETE FROM inventory_allocations WHERE id NOT IN "
                + "(SELECT DISTINCT allocation_id FROM inventory_allocation_lines)");
        jdbcTemplate.update("DELETE FROM inventory_movements WHERE id NOT IN "
                + "(SELECT DISTINCT movement_id FROM inventory_movement_lines)");
        jdbcTemplate.update("DELETE FROM order_item_snapshots WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_confirmation_snapshots WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE customer_name = 'TST-计划客户'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TC0019'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no IN ('TP0009', 'TP0010')");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void persistsPlanOnDraftAndReturnsBatchSnapshot() throws Exception {
        var first = opening("PACKING_BAG", "NONE", 10);
        var second = opening("MAKING", "NONE", 6);

        var created = createOrder("""
                "inventoryPlan":[{"lineNo":1,"batchId":%d,"quantity":4},
                                 {"lineNo":2,"batchId":%d,"quantity":3}]
                """.formatted(first, second))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.inventoryPlan.length()").value(2))
                .andExpect(jsonPath("$.data.inventoryPlan[0].lineNo").value(1))
                .andExpect(jsonPath("$.data.inventoryPlan[0].batchId").value(first))
                .andExpect(jsonPath("$.data.inventoryPlan[0].batchQuantity").value(10))
                .andExpect(jsonPath("$.data.inventoryPlan[0].node").value("PACKING_BAG"))
                .andExpect(jsonPath("$.data.inventoryPlan[0].seamState").value("NONE"))
                .andExpect(jsonPath("$.data.inventoryPlan[1].lineNo").value(2))
                .andExpect(jsonPath("$.data.inventoryPlan[1].quantity").value(3))
                .andReturn();
        long orderId = dataId(created);

        // 重新打开草稿能带出上次选的批次与数量（持久化的意义所在）
        mockMvc.perform(get("/api/orders/" + orderId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryPlan.length()").value(2))
                .andExpect(jsonPath("$.data.inventoryPlan[0].batchNo").isNotEmpty());
        assertThat(countPlanLines(orderId)).isEqualTo(2);
        assertThat(quantityOf(first)).as("保存计划不得占用或扣减库存").isEqualTo(10);
        assertThat(quantityOf(second)).isEqualTo(6);
    }

    @Test
    void replacesAndClearsPlanOnDraftEdit() throws Exception {
        var batch = opening("MAKING", "NONE", 10);
        var created = createOrder("\"inventoryPlan\":[{\"lineNo\":1,\"batchId\":" + batch
                + ",\"quantity\":4}]").andExpect(status().isCreated()).andReturn();
        long orderId = dataId(created);

        // 传非空列表 → 整体替换
        mockMvc.perform(patch("/api/orders/" + orderId)
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"inventoryPlan\":[{\"lineNo\":2,\"batchId\":" + batch
                                + ",\"quantity\":6}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryPlan.length()").value(1))
                .andExpect(jsonPath("$.data.inventoryPlan[0].lineNo").value(2))
                .andExpect(jsonPath("$.data.inventoryPlan[0].quantity").value(6));

        // 传空列表 → 清空计划
        mockMvc.perform(patch("/api/orders/" + orderId)
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"inventoryPlan\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryPlan.length()").value(0));
        assertThat(countPlanLines(orderId)).isZero();
    }

    @Test
    void clearsPlanWhenItemsAreReplacedWithoutNewPlan() throws Exception {
        var batch = opening("MAKING", "NONE", 10);
        var created = createOrder("\"inventoryPlan\":[{\"lineNo\":1,\"batchId\":" + batch
                + ",\"quantity\":4}]").andExpect(status().isCreated()).andReturn();
        long orderId = dataId(created);

        // 明细被整体替换后原计划行引用的明细已不存在，未同时传计划则一并清空
        mockMvc.perform(patch("/api/orders/" + orderId)
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"items":[{"productId":%d,"quantity":8,"seamQuantity":0,
                                 "unitPrice":"10.0000"}]}
                                """.formatted(productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryPlan.length()").value(0));
        assertThat(countPlanLines(orderId)).isZero();
    }

    @Test
    void rejectsInvalidPlanLines() throws Exception {
        var batch = opening("MAKING", "NONE", 10);

        createOrder("\"inventoryPlan\":[{\"lineNo\":1,\"batchId\":999999,\"quantity\":4}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='inventoryPlan[0].batchId')]").isNotEmpty());

        createOrder("\"inventoryPlan\":[{\"lineNo\":9,\"batchId\":" + batch + ",\"quantity\":4}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='inventoryPlan[0].lineNo')]").isNotEmpty());

        createOrder("\"inventoryPlan\":[{\"lineNo\":1,\"batchId\":" + batch + ",\"quantity\":0}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='inventoryPlan[0].quantity')]").isNotEmpty());

        createOrder("\"inventoryPlan\":[{\"lineNo\":1,\"batchId\":" + batch + ",\"quantity\":1},"
                + "{\"lineNo\":1,\"batchId\":" + batch + ",\"quantity\":2}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='inventoryPlan[1].batchId')]").isNotEmpty());
        assertThat(countPlanLinesForTestCustomer()).as("校验失败不得留下计划行").isZero();
    }

    @Test
    void confirmAggregatesPlanPerBatchAndDoesNotOccupyStock() throws Exception {
        var batch = opening("PACKING_BAG", "NONE", 6);
        // 两条明细各计划 5 件：单行都不超，合计 10 件超过批次 6 件，必须按批次聚合才能判出
        var created = createOrder("""
                "inventoryPlan":[{"lineNo":1,"batchId":%d,"quantity":5},
                                 {"lineNo":2,"batchId":%d,"quantity":5}]
                """.formatted(batch, batch))
                .andExpect(status().isCreated())
                .andReturn();
        long orderId = dataId(created);

        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='inventoryPlan')]").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("DRAFT");
        assertThat(quantityOf(batch)).as("确认重验不得占用或扣减库存").isEqualTo(6);

        // 管理员明确转生产后放行；确认仍不创建领用事实、不改批次数量
        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferShortageToProduction\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(quantityOf(batch)).isEqualTo(6);
        assertThat(count("inventory_allocations")).isZero();
    }

    @Test
    void confirmWithoutPlanSkipsRecheck() throws Exception {
        var created = createOrder("").andExpect(status().isCreated()).andReturn();
        long orderId = dataId(created);

        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.inventoryPlan.length()").value(0));
    }

    // ---------- 工具 ----------

    /** 两条明细（订购数量=10、订购数量=10）+ 可选计划片段；金额全部由服务端计算。 */
    private org.springframework.test.web.servlet.ResultActions createOrder(String planFragment) throws Exception {
        var body = """
                {"customerId":%d,"orderDate":"2026-09-25",
                 "items":[{"productId":%d,"quantity":10,"seamQuantity":0,"unitPrice":"10.0000"},
                          {"productId":%d,"quantity":10,"seamQuantity":0,"unitPrice":"10.0000"}]
                 %s}
                """.formatted(customerId(), productId, otherProductId,
                planFragment.isBlank() ? "" : "," + planFragment);
        return mockMvc.perform(post("/api/orders")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long insertProduct(String productNo, String name) {
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5, 10.0000, 100, 6.0000, 10, 5, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, productNo, name);
        return jdbcTemplate.queryForObject("SELECT id FROM products WHERE product_no = ?", Long.class, productNo);
    }

    private long customerId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TC0019'", Long.class);
    }

    private long opening(String node, String seamState, int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/inventory/batches")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"node":"%s","seamState":"%s","quantity":%d,
                                 "inventoryDate":"2026-09-25"}
                                """.formatted(productId, node, seamState, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long dataId(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("id").asLong();
    }

    private int countPlanLines(long orderId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_inventory_plan_lines WHERE order_id = ?", Integer.class, orderId);
        return value == null ? 0 : value;
    }

    private int countPlanLinesForTestCustomer() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_inventory_plan_lines p
                JOIN orders o ON o.id = p.order_id
                WHERE o.customer_name = 'TST-计划客户'
                """, Integer.class);
        return value == null ? 0 : value;
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
