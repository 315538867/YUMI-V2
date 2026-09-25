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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 5.9：未完成待处理（重新安排 / 部分安排 / 暂不安排）。
 * 口径见 `production-management`「未完成数量必须返回对应待处理来源」的 Scenario「部分完成正常计划」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionReminderTest {

    private static final String USERNAME = "reminder-admin";
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
    private long reminderId;

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
                VALUES ('TRM001', 'TST-提醒商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TRM001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TRM001', 'TST-提醒客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TRM001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TRM0001', ?, 'TST-提醒客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 8.0000, 0.0000, 108.0000, 60.0000, 5.0000, 65.0000, 43.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TRM0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TRM001', 'TST-提醒商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    making_inflow, version, created_at, updated_at)
                VALUES (?, ?, 10, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        makerId = insertEmployee("TRE001", "TST-制作员工", "MAKING");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");

        // 计划 10 件，核验完成 6 件 → 未完成 4 件
        long planId = createPlan(10);
        mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":6,\"qualifiedQuantity\":6,\"reworkQuantity\":0,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isOk());
        reminderId = jdbcTemplate.queryForObject("""
                SELECT id FROM production_reminders WHERE reminder_type = 'INCOMPLETE' AND plan_id = ?
                """, Long.class, planId);
    }

    @AfterEach
    void cleanup() {
        var testOrder = "SELECT id FROM orders WHERE order_no = 'TRM0001'";
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
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TRM0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TRM001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TRM001'");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TRE001')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TRE001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void reschedulesPartiallyAndKeepsRemainderInReminder() throws Exception {
        mockMvc.perform(get("/api/production-reminders/incomplete").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].node").value("MAKING"))
                .andExpect(jsonPath("$.data[0].quantity").value(4))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"));

        // 部分安排 3 件：余量 1 继续提醒
        mockMvc.perform(post("/api/production-reminders/incomplete/" + reminderId + "/reschedule")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planDate":"2026-09-26","employeeId":%d,"quantity":3}
                                """.formatted(makerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.quantity").value(1))
                .andExpect(jsonPath("$.data.handlingType").value("PARTIAL"))
                .andExpect(jsonPath("$.data.handledQuantity").value(3));
        assertThat(planCount()).isEqualTo(2);
        assertThat(pendingPlanQuantity()).isEqualTo(3);

        // 超过余量被拒
        mockMvc.perform(post("/api/production-reminders/incomplete/" + reminderId + "/reschedule")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planDate":"2026-09-26","employeeId":%d,"quantity":2}
                                """.formatted(makerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUANTITY_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='quantity')]").isNotEmpty());

        // 安排余下 1 件 → 提醒关闭
        mockMvc.perform(post("/api/production-reminders/incomplete/" + reminderId + "/reschedule")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planDate":"2026-09-27","employeeId":%d,"quantity":1}
                                """.formatted(makerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HANDLED"))
                .andExpect(jsonPath("$.data.handlingType").value("RESCHEDULED"));
        mockMvc.perform(get("/api/production-reminders/incomplete").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(pendingPlanQuantity()).isEqualTo(4);
    }

    @Test
    void defersWithReasonAndKeepsDemand() throws Exception {
        mockMvc.perform(post("/api/production-reminders/incomplete/" + reminderId + "/defer")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());

        mockMvc.perform(post("/api/production-reminders/incomplete/" + reminderId + "/defer")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"等待材料到货\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HANDLED"))
                .andExpect(jsonPath("$.data.handlingType").value("DEFERRED"))
                .andExpect(jsonPath("$.data.reason").value("等待材料到货"));

        // 暂不安排不删除待安排需求：未完成 4 件仍在待安排里（需求 10 − 待执行计划 0 − 已核验 6 = 4）
        assertThat(schedulable()).isEqualTo(4);
        assertThat(planCount()).isEqualTo(1);
    }

    @Test
    void rejectsRescheduleOnHandledReminder() throws Exception {
        mockMvc.perform(post("/api/production-reminders/incomplete/" + reminderId + "/defer")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"暂不安排\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/production-reminders/incomplete/" + reminderId + "/reschedule")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planDate":"2026-09-26","employeeId":%d,"quantity":1}
                                """.formatted(makerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));
    }

    // ---------- 工具 ----------

    private long createPlan(int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/production-plans")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planType":"NORMAL","orderItemId":%d,"node":"MAKING","planDate":"2026-09-25",
                                 "employeeId":%d,"quantity":%d}
                                """.formatted(orderItemId, makerId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
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

    private int planCount() {
        var value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM production_plans WHERE order_id = ?", Integer.class, orderId);
        return value == null ? 0 : value;
    }

    private int pendingPlanQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM production_plans
                WHERE order_id = ? AND status = 'PENDING'
                """, Integer.class, orderId);
        return value == null ? 0 : value;
    }

    private int schedulable() {
        return 10 - pendingPlanQuantity() - 6;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
