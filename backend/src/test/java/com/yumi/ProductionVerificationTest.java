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
 * 任务 5.4/5.5：一次性核验、等式与可执行上限、逐工序合格流转、返工/重做来源、未完成提醒。
 * 口径见 `domain-and-quantity-model.md` §7 与施工文档 §4.3。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionVerificationTest {

    private static final String USERNAME = "production-verify-admin";
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
                VALUES ('TVR001', 'TST-核验商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TVR001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TVR001', 'TST-核验客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TVR001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TVR0001', ?, 'TST-核验客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 8.0000, 0.0000, 108.0000, 60.0000, 5.0000, 65.0000, 43.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TVR0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TVR001', 'TST-核验商品', 10, 4, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        // 制作工序有效流入 10，使计划可执行
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    making_inflow, version, created_at, updated_at)
                VALUES (?, ?, 10, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        makerId = insertEmployee("TVE001", "TST-制作员工", "MAKING");
        packerId = insertEmployee("TVE002", "TST-包装员工", "PACKING_BAG");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrder = "SELECT id FROM orders WHERE order_no = 'TVR0001'";
        var testPlans = "SELECT id FROM production_plans WHERE order_id IN (" + testOrder + ")";
        jdbcTemplate.update("DELETE FROM production_reminders WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM overtime_preemptions WHERE overtime_plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM remake_sources WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM rework_sources WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM production_verifications WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plan_adjustments WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plans WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TVR0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TVR001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TVR001'");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TVE001', 'TVE002'))");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no IN ('TVE001', 'TVE002')");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void verifiesMakingPlanAndFlowsQualifiedToPacking() throws Exception {
        long planId = createPlan("MAKING", makerId, 10);

        mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"completedQuantity":10,"qualifiedQuantity":8,"reworkQuantity":1,
                                 "scrapQuantity":1,"verifyNote":"首件合格"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.node").value("MAKING"))
                .andExpect(jsonPath("$.data.completedQuantity").value(10))
                .andExpect(jsonPath("$.data.incompleteQuantity").value(0))
                .andExpect(jsonPath("$.data.flows.length()").value(1))
                .andExpect(jsonPath("$.data.flows[0].node").value("PACKING_BAG"))
                .andExpect(jsonPath("$.data.flows[0].quantity").value(8))
                .andExpect(jsonPath("$.data.reworkSourceId").doesNotExist())
                .andExpect(jsonPath("$.data.remakeSourceId").doesNotExist())
                .andExpect(jsonPath("$.data.incompleteReminderId").doesNotExist());

        // 投影：合格流入捏毛装袋、计划占用释放、已核验处理累加、返工/重做待安排
        var balance = jdbcTemplate.queryForMap("""
                SELECT packing_inflow, making_planned, verified_processed, rework_pending, remake_pending
                FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, orderItemId);
        assertThat(((Number) balance.get("packing_inflow")).intValue()).isEqualTo(8);
        assertThat(((Number) balance.get("making_planned")).intValue()).isZero();
        assertThat(((Number) balance.get("verified_processed")).intValue()).isEqualTo(10);
        assertThat(((Number) balance.get("rework_pending")).intValue()).isEqualTo(1);
        assertThat(((Number) balance.get("remake_pending")).intValue()).isEqualTo(1);

        // 履约事实：一条 PRODUCTION_QUALIFIED 流入捏毛装袋
        var entry = jdbcTemplate.queryForMap("""
                SELECT entry_type, node, direction, quantity, source_type, source_id
                FROM fulfillment_entries WHERE order_item_id = ? AND entry_type = 'PRODUCTION_QUALIFIED'
                """, orderItemId);
        assertThat(entry.get("node")).isEqualTo("PACKING_BAG");
        assertThat(entry.get("direction")).isEqualTo("IN");
        assertThat(((Number) entry.get("quantity")).intValue()).isEqualTo(8);
        assertThat(entry.get("source_type")).isEqualTo("PRODUCTION");

        // 返工/报废额度进入「待安排」，来源由 5.6/5.7 按目标工序显式创建
        assertThat(count("rework_sources")).isZero();
        assertThat(count("remake_sources")).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM production_plans WHERE id = ?", String.class, planId))
                .isEqualTo("VERIFIED");
    }

    @Test
    void splitsPackingBagQualifiedByFrozenSeamQuantity() throws Exception {
        // 捏毛装袋已有 10 件流入（制作合格或库存接入），冻结缝边数量=4
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET packing_inflow = 10 "
                + "WHERE order_item_id = ?", orderItemId);
        long planId = createPlan("PACKING_BAG", packerId, 10);

        mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":10,\"qualifiedQuantity\":10,\"reworkQuantity\":0,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flows.length()").value(2))
                .andExpect(jsonPath("$.data.flows[0].node").value("SEAM_CUTTING"))
                .andExpect(jsonPath("$.data.flows[0].quantity").value(4))
                .andExpect(jsonPath("$.data.flows[1].node").value("SHIPPABLE"))
                .andExpect(jsonPath("$.data.flows[1].quantity").value(6));

        var balance = jdbcTemplate.queryForMap("""
                SELECT seam_inflow, shippable_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, orderItemId);
        assertThat(((Number) balance.get("seam_inflow")).intValue()).isEqualTo(4);
        assertThat(((Number) balance.get("shippable_quantity")).intValue()).isEqualTo(6);
    }

    @Test
    void rejectsEquationViolationAndOverExecutable() throws Exception {
        long planId = createPlan("MAKING", makerId, 10);

        // 完成 ≠ 合格 + 返工 + 报废
        mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":10,\"qualifiedQuantity\":8,\"reworkQuantity\":1,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_EQUATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='completedQuantity')]").isNotEmpty());

        // 本次完成超过计划数量
        mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":11,\"qualifiedQuantity\":11,\"reworkQuantity\":0,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_EQUATION_INVALID"));

        // 可执行上限在事务内重算：下游工序（捏毛装袋）流入只有 5，完成 10 超限
        // （首道制作的有效流入是订单需求（订购数量），不受 making_inflow 影响）
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET packing_inflow = 5 "
                + "WHERE order_item_id = ?", orderItemId);
        long packingPlanId = createPlan("PACKING_BAG", packerId, 10);
        mockMvc.perform(post("/api/production-plans/" + packingPlanId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":10,\"qualifiedQuantity\":10,\"reworkQuantity\":0,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_NOT_EXECUTABLE"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='completedQuantity')]").isNotEmpty());

        assertThat(count("production_verifications")).as("校验失败不得留下核验事实").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM production_plans WHERE id = ?", String.class, planId)).isEqualTo("PENDING");
    }

    @Test
    void rejectsSecondVerification() throws Exception {
        long planId = createPlan("MAKING", makerId, 4);
        verify(planId, 4, 4, 0, 0).andExpect(status().isOk());

        verify(planId, 4, 4, 0, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_ALREADY_VERIFIED"));
        assertThat(count("production_verifications")).as("原核验不变且不追加").isEqualTo(1);
    }

    @Test
    void createsIncompleteReminderAndReturnsQuantityToSchedulable() throws Exception {
        long planId = createPlan("MAKING", makerId, 10);
        verify(planId, 6, 6, 0, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incompleteQuantity").value(4))
                .andExpect(jsonPath("$.data.incompleteReminderId").isNotEmpty())
                .andExpect(jsonPath("$.data.reworkSourceId").doesNotExist())
                .andExpect(jsonPath("$.data.remakeSourceId").doesNotExist());

        var reminder = jdbcTemplate.queryForMap("""
                SELECT reminder_type, node, quantity, status FROM production_reminders WHERE plan_id = ?
                """, planId);
        assertThat(reminder.get("reminder_type")).isEqualTo("INCOMPLETE");
        assertThat(reminder.get("node")).isEqualTo("MAKING");
        assertThat(((Number) reminder.get("quantity")).intValue()).isEqualTo(4);
        assertThat(reminder.get("status")).isEqualTo("OPEN");

        // 未完成 4 件回到待安排：需求 10 − 待执行计划 0 − 已核验 6 = 4
        mockMvc.perform(get("/api/production-plans/" + planId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schedulableQuantity").value(4))
                .andExpect(jsonPath("$.data.nodeVerified").value(6));
        // 剩余 4 件可以重新排产
        createPlan("MAKING", makerId, 4);
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions verify(long planId, int completed, int qualified,
                                                                     int rework, int scrap) throws Exception {
        return mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"completedQuantity":%d,"qualifiedQuantity":%d,"reworkQuantity":%d,"scrapQuantity":%d}
                        """.formatted(completed, qualified, rework, scrap)));
    }

    private long createPlan(String node, long employeeId, int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/production-plans")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planType":"NORMAL","orderItemId":%d,"node":"%s","planDate":"2026-09-25",
                                 "employeeId":%d,"quantity":%d}
                                """.formatted(orderItemId, node, employeeId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node0 = objectMapper.readTree(created.getResponse().getContentAsString());
        return node0.path("data").path("id").asLong();
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

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
