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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 8.4/8.5：售后返工来源与售后补发生产——复用生产计划一次核验，合格按售后用途进入可补发，
 * 不进入订单履约、不自动进入通用库存；报废不入库、不恢复库存、不计已补发；
 * 库存不足部分只能创建关联售后单与原订单明细的售后补发生产计划。
 * 口径见 `docs/architecture/after-sales-module-design.md` §4.3 与 `domain-and-quantity-model.md` §12。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AfterSalesProductionTest {

    private static final String USERNAME = "after-sales-production-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String PLAN_DATE = "2026-09-26";

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
    private long makerId;

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
                VALUES ('TAP001', 'TST-售后生产商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TAP001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TAP001', 'TST-售后生产客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TAP001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TAP0001', ?, 'TST-售后生产客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TAP0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TAP001', 'TST-售后生产商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
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
                VALUES ('SH970001', ?, 'CONFIRMED', '2026-09-25', 0.0000, 0.0000, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, orderId);
        long shipmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = 'SH970001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 4, 'TAP001', 'TST-售后生产商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, shipmentId, orderItemId);
        long shipmentItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = ?", Long.class, shipmentId);
        makerId = insertEmployee("TPA001", "TST-售后生产制作工", "MAKING");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
        // 售后单：受理 4、退回核验 3 = 返工 2 + 报废 1、补发需求 4
        var created = mockMvc.perform(post("/api/orders/" + orderId + "/after-sales")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseType":"REWORK_AND_REPLACEMENT","problem":"客户反馈破损",
                                 "items":[{"shipmentItemId":%d,"acceptedQuantity":4,"returnedQuantity":3,
                                 "replacementRequiredQuantity":4}]}
                                """.formatted(shipmentItemId)))
                .andExpect(status().isCreated())
                .andReturn();
        caseId = dataId(created);
        itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_items WHERE case_id = ?", Long.class, caseId);
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TAP0001'";
        var cases = "SELECT id FROM after_sales_cases WHERE order_id IN (" + testOrders + ")";
        var items = "SELECT id FROM after_sales_items WHERE case_id IN (" + cases + ")";
        var shipments = "SELECT id FROM shipments WHERE order_id IN (" + testOrders + ")";
        jdbcTemplate.update("DELETE FROM after_sales_production_sources WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_corrections WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_fulfillment_entries WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_shipment_links WHERE after_sales_item_id IN (" + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_return_verifications WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_items WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM production_verifications WHERE plan_id IN "
                + "(SELECT id FROM production_plans WHERE order_id IN (" + testOrders + "))");
        jdbcTemplate.update("DELETE FROM production_plans WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM shipment_source_links WHERE shipment_item_id IN "
                + "(SELECT id FROM shipment_items WHERE shipment_id IN (" + shipments + "))");
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TAP0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TAP001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TAP001'");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPA001')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPA001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void reworkPlanFeedsAvailableQuantityWithoutTouchingOrderFulfillment() throws Exception {
        // 未核验退回前没有返工额度
        createPlan("REWORK", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));

        verifyReturn(3, 2, 1).andExpect(status().isOk());
        // 返工额度 = 退回核验的返工数量 2
        createPlan("REWORK", 3).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='quantity')]").isNotEmpty());

        int fulfillmentBefore = count("fulfillment_entries");
        String balancesBefore = balanceSnapshot();
        var created = createPlan("REWORK", 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planType").value("AFTER_SALES_REWORK"))
                .andExpect(jsonPath("$.data.sourceType").value("AFTER_SALES_SOURCE"))
                .andReturn();
        long planId = dataId(created);
        // 售后计划不占用订单工序需求
        assertThat(count("production_plans")).isEqualTo(1);
        assertThat(balanceSnapshot()).as("售后计划不得改变订单履约投影").isEqualTo(balancesBefore);

        mockMvc.perform(post("/api/production-plans/" + planId + "/verify").cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"completedQuantity":2,"qualifiedQuantity":2,"reworkQuantity":0,
                                 "scrapQuantity":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qualifiedQuantity").value(2))
                .andExpect(jsonPath("$.data.flows[0].node").value("AFTER_SALES_AVAILABLE"));

        // 合格只增加售后可补发
        assertThat(availableQuantity()).as("售后可补发 +2").isEqualTo(2);
        assertThat(shippedQuantity()).as("已补发不因合格增加").isZero();
        assertThat(count("fulfillment_entries")).as("不产生订单履约事实").isEqualTo(fulfillmentBefore);
        assertThat(balanceSnapshot()).as("订单履约投影不变").isEqualTo(balancesBefore);
        // 不自动进入通用库存、也不进订单工序流入
        assertThat(count("inventory_batches")).as("不自动新建库存批次").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM after_sales_fulfillment_entries WHERE after_sales_item_id = ?
                """, Integer.class, itemId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT entry_type FROM after_sales_fulfillment_entries WHERE after_sales_item_id = ?
                """, String.class, itemId)).isEqualTo("PRODUCTION_INFLOW");
    }

    @Test
    void scrapStaysOutOfInventoryAndReplacementPlanCoversShortage() throws Exception {
        verifyReturn(3, 2, 1).andExpect(status().isOk());
        // 报废 1：不入库、不恢复库存、不计已补发
        assertThat(count("inventory_batches")).isZero();
        assertThat(count("inventory_movements")).isZero();
        assertThat(availableQuantity()).isZero();
        assertThat(shippedQuantity()).isZero();

        // 补发缺口 = 补发需求 4 − 已补发 0 − 可补发 0 = 4；超量被拒
        createPlan("REPLACEMENT", 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));
        var created = createPlan("REPLACEMENT", 4).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planType").value("AFTER_SALES_REPLACEMENT"))
                .andReturn();
        long planId = dataId(created);
        // 来源行记录额度与占用，读模型可见
        mockMvc.perform(get("/api/after-sales/" + caseId + "/production-sources").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.purpose=='REPLACEMENT')].totalQuantity").value(4))
                .andExpect(jsonPath("$.data[?(@.purpose=='REPLACEMENT')].arrangedQuantity").value(4))
                .andExpect(jsonPath("$.data[?(@.purpose=='REPLACEMENT')].balance").value(0));

        mockMvc.perform(post("/api/production-plans/" + planId + "/verify").cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"completedQuantity":4,"qualifiedQuantity":4,"reworkQuantity":0,
                                 "scrapQuantity":0}
                                """))
                .andExpect(status().isOk());
        assertThat(availableQuantity()).as("生产合格进入可补发 4").isEqualTo(4);
        assertThat(count("inventory_batches")).as("合格品不自动进入通用库存").isZero();

        // 可补发已覆盖需求：不再允许为同一需求排产
        createPlan("REPLACEMENT", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));
    }

    @Test
    void incompleteReworkQuantityReturnsToSourceForRescheduling() throws Exception {
        verifyReturn(3, 2, 1).andExpect(status().isOk());
        var created = createPlan("REWORK", 2).andExpect(status().isCreated()).andReturn();
        long planId = dataId(created);

        // 完成 1 = 合格 1；未完成 1 退回来源余额
        mockMvc.perform(post("/api/production-plans/" + planId + "/verify").cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"completedQuantity":1,"qualifiedQuantity":1,"reworkQuantity":0,
                                 "scrapQuantity":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incompleteQuantity").value(1));
        assertThat(availableQuantity()).isEqualTo(1);
        assertThat(sourceBalance("REWORK")).as("未完成 1 回到来源余额").isEqualTo(1);
        // 余额可再次排产
        createPlan("REWORK", 1).andExpect(status().isCreated());
        createPlan("REWORK", 1).andExpect(status().isConflict());
    }

    @Test
    void rejectsPlanForItemOfAnotherCaseAndBadRequests() throws Exception {
        verifyReturn(3, 2, 1).andExpect(status().isOk());
        mockMvc.perform(post("/api/after-sales/999999/production-sources/plans").cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"afterSalesItemId":%d,"purpose":"REWORK","node":"MAKING","planDate":"%s",
                                 "employeeId":%d,"quantity":1}
                                """.formatted(itemId, PLAN_DATE, makerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
        mockMvc.perform(post("/api/after-sales/" + caseId + "/production-sources/plans").cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"afterSalesItemId":%d,"purpose":"SCRAP","node":"MAKING","planDate":"%s",
                                 "employeeId":%d,"quantity":1}
                                """.formatted(itemId, PLAN_DATE, makerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='purpose')]").isNotEmpty());
        assertThat(count("after_sales_production_sources")).isZero();
        assertThat(count("production_plans")).isZero();
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions createPlan(String purpose, int quantity)
            throws Exception {
        return mockMvc.perform(post("/api/after-sales/" + caseId + "/production-sources/plans")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"afterSalesItemId":%d,"purpose":"%s","node":"MAKING","planDate":"%s",
                         "employeeId":%d,"quantity":%d}
                        """.formatted(itemId, purpose, PLAN_DATE, makerId, quantity)));
    }

    private org.springframework.test.web.servlet.ResultActions verifyReturn(int returned, int rework, int scrap)
            throws Exception {
        return mockMvc.perform(post("/api/after-sales/" + caseId + "/verify-return").cookie(sessionCookie)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"afterSalesItemId":%d,"returnedQuantity":%d,"reworkQuantity":%d,"scrapQuantity":%d,
                         "reason":"客户退回"}
                        """.formatted(itemId, returned, rework, scrap)));
    }

    private long insertEmployee(String employeeNo, String name, String workTypeCode) {
        jdbcTemplate.update("""
                INSERT INTO employees (employee_no, name, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, employeeNo, name);
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM employees WHERE employee_no = ?", Long.class, employeeNo);
        jdbcTemplate.update("""
                INSERT INTO employee_work_types (employee_id, work_type_id, created_at)
                SELECT ?, id, UTC_TIMESTAMP(6) FROM work_types WHERE code = ?
                """, id, workTypeCode);
        return id;
    }

    private int availableQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM after_sales_fulfillment_entries WHERE after_sales_item_id = ?
                """, Integer.class, itemId);
        return value == null ? 0 : value;
    }

    private int shippedQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM after_sales_fulfillment_entries
                WHERE after_sales_item_id = ? AND entry_type = 'REPLACEMENT_CONSUME' AND direction = 'OUT'
                """, Integer.class, itemId);
        return value == null ? 0 : value;
    }

    private int sourceBalance(String purpose) {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(total_quantity - arranged_quantity), 0)
                FROM after_sales_production_sources WHERE after_sales_item_id = ? AND purpose = ?
                """, Integer.class, itemId, purpose);
        return value == null ? 0 : value;
    }

    /** 订单履约投影快照：售后生产不得改变订单侧排产与履约数量。 */
    private String balanceSnapshot() {
        return jdbcTemplate.queryForObject("""
                SELECT CONCAT(required_quantity, '/', shippable_quantity, '/', shipped_quantity, '/',
                              making_planned, '/', packing_planned, '/', seam_planned, '/', verified_processed)
                FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, String.class, orderItemId);
    }

    private long dataId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private static String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
