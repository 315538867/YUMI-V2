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
 * 任务 5.2/5.3：生产计划创建与查询、待安排/当前可执行/等待上游派生、执行员工资格、计划占用投影。
 * 计划创建不产生完成、库存或履约事实（`production-management` 的「创建等待上游计划」Scenario）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionPlanApiTest {

    private static final String USERNAME = "production-plan-admin";
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
    private long makerId;
    private long unskilledId;
    private long cutterId;
    private long packerId;

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
                VALUES ('TPR001', 'TST-排产商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TPR001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TCR001', 'TST-排产客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TCR001'", Long.class);
        // 订购数量=10、缝边数量=4 的已确认订单
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TPR0001', ?, 'TST-排产客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 8.0000, 0.0000, 108.0000, 60.0000, 5.0000, 65.0000, 43.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TPR0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TPR001', 'TST-排产商品', 10, 4, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    version, created_at, updated_at)
                VALUES (?, ?, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        makerId = insertEmployee("TPE001", "TST-制作员工", "MAKING");
        unskilledId = insertEmployee("TPE002", "TST-无资格员工", null);
        cutterId = insertEmployee("TPE003", "TST-缝边员工", "SEAM_CUTTING");
        packerId = insertEmployee("TPE004", "TST-包装员工", "PACKING_BAG");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrder = "SELECT id FROM orders WHERE order_no = 'TPR0001'";
        var testPlans = "SELECT id FROM production_plans WHERE order_id IN (" + testOrder + ")";
        jdbcTemplate.update("DELETE FROM production_reminders WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM overtime_preemptions WHERE overtime_plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM remake_sources WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM rework_sources WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM production_verifications WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plan_adjustments WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plans WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TPR0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TCR001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPR001'");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TPE001', 'TPE002', 'TPE003', 'TPE004'))");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no IN "
                + "('TPE001', 'TPE002', 'TPE003', 'TPE004')");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void createsWaitingUpstreamPlanWithoutProducingFulfillmentFacts() throws Exception {
        int factsBefore = count("fulfillment_entries");

        // 等待上游针对**下游工序**：捏毛装袋尚无上游合格流入或库存接入
        var created = createPlan("PACKING_BAG", "2026-09-25", packerId, 10)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planNo").value(org.hamcrest.Matchers.startsWith("PN")))
                .andExpect(jsonPath("$.data.planType").value("NORMAL"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.employeeName").value("TST-包装员工"))
                .andExpect(jsonPath("$.data.nodeDemand").value(10))
                .andExpect(jsonPath("$.data.nodePending").value(10))
                .andExpect(jsonPath("$.data.schedulableQuantity").value(0))
                .andExpect(jsonPath("$.data.executableQuantity").value(0))
                .andExpect(jsonPath("$.data.waitingUpstream").value(true))
                .andReturn();
        long planId = dataId(created);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT packing_planned FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, Integer.class, orderItemId)).as("计划占用写入排产投影").isEqualTo(10);
        assertThat(count("fulfillment_entries")).as("计划创建不产生履约事实").isEqualTo(factsBefore);
        assertThat(count("inventory_movements")).as("计划创建不产生库存事实").isZero();

        // 缝边剪袋的总需求是冻结的缝边数量=4，不是订购数量
        createPlan("SEAM_CUTTING", "2026-09-25", cutterId, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nodeDemand").value(4));
        createPlan("SEAM_CUTTING", "2026-09-26", cutterId, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='quantity')]").isNotEmpty());
        assertThat(planId).isPositive();
    }

    @Test
    void derivesExecutableQuantityByPlanOrder() throws Exception {
        // 制作工序有效流入 10（模拟上游库存接入或上游核验合格）
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET making_inflow = 10 "
                + "WHERE order_item_id = ?", orderItemId);

        var first = createPlan("MAKING", "2026-09-25", makerId, 6)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nodeInflow").value(10))
                .andExpect(jsonPath("$.data.executableQuantity").value(6))
                .andExpect(jsonPath("$.data.waitingUpstream").value(false))
                .andReturn();
        long firstId = dataId(first);

        // 第二个计划只能拿到剩下的 4 份可执行量，先到先得；待安排上限也是 4
        var second = createPlan("MAKING", "2026-09-26", makerId, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.schedulableQuantity").value(0))
                .andExpect(jsonPath("$.data.executableQuantity").value(4))
                .andReturn();
        long secondId = dataId(second);

        mockMvc.perform(get("/api/production-plans/" + firstId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executableQuantity").value(6));
        mockMvc.perform(get("/api/production-plans/" + secondId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executableQuantity").value(4));
    }

    @Test
    void rejectsIneligibleEmployee() throws Exception {
        createPlan("MAKING", "2026-09-25", unskilledId, 5)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_ELIGIBLE"));
        assertThat(count("production_plans")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT making_planned FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, Integer.class, orderItemId)).as("失败不得留下计划占用").isZero();
    }

    @Test
    void rejectsQuantityAboveSchedulableAndBadRequests() throws Exception {
        createPlan("MAKING", "2026-09-25", makerId, 11)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUANTITY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='quantity')]").isNotEmpty());

        createPlan("REWORK", "MAKING", "2026-09-25", makerId, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='planType')]").isNotEmpty());

        createPlan("MAKING", "2026-09-25", makerId, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='quantity')]").isNotEmpty());

        // 草稿订单不可排产
        jdbcTemplate.update("UPDATE orders SET status = 'DRAFT' WHERE id = ?", orderId);
        createPlan("MAKING", "2026-09-25", makerId, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='orderItemId')]").isNotEmpty());
        assertThat(count("production_plans")).isZero();
    }

    @Test
    void filtersPlansByNodeEmployeeStatusAndDate() throws Exception {
        createPlan("MAKING", "2026-09-25", makerId, 4).andExpect(status().isCreated());
        createPlan("PACKING_BAG", "2026-09-26", packerId, 4).andExpect(status().isCreated());

        mockMvc.perform(get("/api/production-plans").cookie(sessionCookie)
                        .param("orderItemId", String.valueOf(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/api/production-plans").cookie(sessionCookie)
                        .param("node", "MAKING").param("orderItemId", String.valueOf(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].node").value("MAKING"));
        mockMvc.perform(get("/api/production-plans").cookie(sessionCookie)
                        .param("dateFrom", "2026-09-26").param("orderItemId", String.valueOf(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].node").value("PACKING_BAG"));
        mockMvc.perform(get("/api/production-plans").cookie(sessionCookie)
                        .param("status", "VERIFIED").param("orderItemId", String.valueOf(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions createPlan(String node, String planDate,
                                                                         long employeeId, int quantity)
            throws Exception {
        return createPlan("NORMAL", node, planDate, employeeId, quantity);
    }

    private org.springframework.test.web.servlet.ResultActions createPlan(String planType, String node,
                                                                         String planDate, long employeeId,
                                                                         int quantity) throws Exception {
        return mockMvc.perform(post("/api/production-plans")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"planType":"%s","orderItemId":%d,"node":"%s","planDate":"%s",
                         "employeeId":%d,"quantity":%d}
                        """.formatted(planType, orderItemId, node, planDate, employeeId, quantity)));
    }

    private long insertEmployee(String employeeNo, String name, String workTypeCode) {
        jdbcTemplate.update("""
                INSERT INTO employees (employee_no, name, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, employeeNo, name);
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM employees WHERE employee_no = ?", Long.class, employeeNo);
        if (workTypeCode != null) {
            jdbcTemplate.update("""
                    INSERT INTO employee_work_types (employee_id, work_type_id, created_at)
                    SELECT ?, id, UTC_TIMESTAMP(6) FROM work_types WHERE code = ?
                    """, id, workTypeCode);
        }
        return id;
    }

    private long dataId(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("id").asLong();
    }

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
