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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 5.10/5.11/5.12：超额任务创建与预占（当天、未来来源、不超未预占余额）、核验后的预占释放与
 * 计划待调整提醒、提醒的人工处理（调整计划 / 无需调整）。
 * 口径见 `domain-and-quantity-model.md` §11 与施工文档 §4.6。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OvertimeTaskTest {

    private static final String USERNAME = "overtime-admin";
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
    private long futurePlanId;

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
                VALUES ('TOT001', 'TST-超额商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TOT001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TOT001', 'TST-超额客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TOT001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TOT0001', ?, 'TST-超额客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 8.0000, 0.0000, 108.0000, 60.0000, 5.0000, 65.0000, 43.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TOT0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TOT001', 'TST-超额商品', 10, 4, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    making_inflow, version, created_at, updated_at)
                VALUES (?, ?, 10, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        makerId = insertEmployee("TOE001", "TST-制作员工", "MAKING");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
        // 未来日期的正常计划（明天 10 件），作为超额任务的产能来源
        futurePlanId = createNormalPlan(LocalDate.now().plusDays(1), 4);
    }

    @AfterEach
    void cleanup() {
        var testOrder = "SELECT id FROM orders WHERE order_no = 'TOT0001'";
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
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TOT0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TOT001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TOT001'");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TOE001')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TOE001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void createsOvertimeTaskWithPreemptionWithoutTouchingFuturePlan() throws Exception {
        int factsBefore = count("fulfillment_entries");
        var created = createOvertimeTask(LocalDate.now(), futurePlanId, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planNo").value(org.hamcrest.Matchers.startsWith("PN")))
                .andExpect(jsonPath("$.data.quantity").value(4))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.preemptions.length()").value(1))
                .andExpect(jsonPath("$.data.preemptions[0].futurePlanId").value(futurePlanId))
                .andExpect(jsonPath("$.data.preemptions[0].preemptedQuantity").value(4))
                .andExpect(jsonPath("$.data.preemptions[0].status").value("ACTIVE"))
                .andReturn();
        long overtimePlanId = dataPlanId(created);

        // 预占不修改未来计划原始数量、不产生履约事实
        assertThat(planQuantity(futurePlanId)).isEqualTo(4);
        assertThat(count("fulfillment_entries")).isEqualTo(factsBefore);
        // 创建时生成「超额待核验」提醒并附着在受影响未来计划行上
        var reminder = jdbcTemplate.queryForMap("""
                SELECT reminder_type, future_plan_id, plan_id, quantity, status
                FROM production_reminders WHERE reminder_type = 'OVERTIME_PENDING_VERIFY' AND plan_id = ?
                """, overtimePlanId);
        assertThat(((Number) reminder.get("future_plan_id")).longValue()).isEqualTo(futurePlanId);
        assertThat(((Number) reminder.get("quantity")).intValue()).isEqualTo(4);
        assertThat(reminder.get("status")).isEqualTo("OPEN");

        // 未预占余额：4 − 4 = 0，再要 8 件被拒
        createOvertimeTask(LocalDate.now(), futurePlanId, 8)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERTIME_RESERVATION_EXCEEDED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='lines')]").isNotEmpty());
        assertThat(planQuantity(futurePlanId)).isEqualTo(4);
    }

    @Test
    void rejectsNonTodayDateAndNonFutureSource() throws Exception {
        createOvertimeTask(LocalDate.now().minusDays(1), futurePlanId, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OVERTIME_DATE_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='planDate')]").isNotEmpty());

        // 当天正常任务不能作为来源（来源必须是未来日期）
        long todayPlanId = createNormalPlan(LocalDate.now(), 2);
        createOvertimeTask(LocalDate.now(), todayPlanId, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OVERTIME_DATE_INVALID"));

        assertThat(count("production_plans")).isEqualTo(2);
    }

    @Test
    void settlesPreemptionAndCreatesPlanAdjustmentReminderOnVerify() throws Exception {
        long overtimePlanId = dataPlanId(createOvertimeTask(LocalDate.now(), futurePlanId, 4)
                .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(post("/api/overtime-tasks/" + overtimePlanId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":4,\"qualifiedQuantity\":4,\"reworkQuantity\":0,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flows[0].node").value("PACKING_BAG"))
                .andExpect(jsonPath("$.data.flows[0].quantity").value(4));

        // 预占释放；待核验提醒被计划待调整接管
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM overtime_preemptions WHERE overtime_plan_id = ?", String.class,
                overtimePlanId)).isEqualTo("RELEASED");
        var pending = jdbcTemplate.queryForMap("""
                SELECT status, handling_type FROM production_reminders
                WHERE reminder_type = 'OVERTIME_PENDING_VERIFY' AND plan_id = ?
                """, overtimePlanId);
        assertThat(pending.get("status")).isEqualTo("HANDLED");
        assertThat(pending.get("handling_type")).isEqualTo("SUPERSEDED");

        // 合格 4 件 → 受影响未来计划生成「计划待调整」，建议数量按合格数量计算
        mockMvc.perform(get("/api/production-reminders/overtime").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reminderType").value("PLAN_ADJUSTMENT"))
                .andExpect(jsonPath("$.data[0].futurePlanId").value(futurePlanId))
                .andExpect(jsonPath("$.data[0].quantity").value(4));
        long reminderId = jdbcTemplate.queryForObject("""
                SELECT id FROM production_reminders WHERE reminder_type = 'PLAN_ADJUSTMENT' AND plan_id = ?
                """, Long.class, overtimePlanId);

        // 调整未来计划：留痕（前后数量 + 原因）并改计划数量与计划占用投影
        int plannedBefore = makingPlanned();
        mockMvc.perform(post("/api/production-reminders/overtime/" + reminderId + "/adjust-plan")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newQuantity\":1,\"reason\":\"超额已提前完成 4 件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HANDLED"))
                .andExpect(jsonPath("$.data.handlingType").value("ADJUSTED"));
        assertThat(planQuantity(futurePlanId)).isEqualTo(1);
        var adjustment = jdbcTemplate.queryForMap("""
                SELECT adjustment_type, before_quantity, after_quantity, reason
                FROM production_plan_adjustments WHERE plan_id = ?
                """, futurePlanId);
        assertThat(adjustment.get("adjustment_type")).isEqualTo("QUANTITY");
        assertThat(((Number) adjustment.get("before_quantity")).intValue()).isEqualTo(4);
        assertThat(((Number) adjustment.get("after_quantity")).intValue()).isEqualTo(1);
        assertThat(adjustment.get("reason")).isEqualTo("超额已提前完成 4 件");
        assertThat(makingPlanned()).isEqualTo(plannedBefore - 3);
    }

    @Test
    void zeroQualifiedEndsPendingReminderWithoutAdjustment() throws Exception {
        long overtimePlanId = dataPlanId(createOvertimeTask(LocalDate.now(), futurePlanId, 3)
                .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(post("/api/overtime-tasks/" + overtimePlanId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":3,\"qualifiedQuantity\":0,\"reworkQuantity\":2,"
                                + "\"scrapQuantity\":1}"))
                .andExpect(status().isOk());

        var pending = jdbcTemplate.queryForMap("""
                SELECT status, handling_type, reason FROM production_reminders
                WHERE reminder_type = 'OVERTIME_PENDING_VERIFY' AND plan_id = ?
                """, overtimePlanId);
        assertThat(pending.get("status")).isEqualTo("HANDLED");
        assertThat(pending.get("handling_type")).isEqualTo("NO_ADJUSTMENT");
        assertThat(String.valueOf(pending.get("reason"))).contains("零合格");
        // 不生成计划减少建议；超额任务的未完成不进入普通未完成提醒
        assertThat(count("production_reminders")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM overtime_preemptions WHERE overtime_plan_id = ?", String.class,
                overtimePlanId)).isEqualTo("RELEASED");
        // 返工/报废按独立来源处理，只进待安排额度
        assertThat(reworkPending()).isEqualTo(2);
        assertThat(remakePending()).isEqualTo(1);
    }

    @Test
    void requiresReasonForNoAdjustment() throws Exception {
        long overtimePlanId = dataPlanId(createOvertimeTask(LocalDate.now(), futurePlanId, 2)
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/overtime-tasks/" + overtimePlanId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":2,\"qualifiedQuantity\":2,\"reworkQuantity\":0,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isOk());
        long reminderId = jdbcTemplate.queryForObject("""
                SELECT id FROM production_reminders WHERE reminder_type = 'PLAN_ADJUSTMENT' AND plan_id = ?
                """, Long.class, overtimePlanId);

        mockMvc.perform(post("/api/production-reminders/overtime/" + reminderId + "/no-adjustment")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='reason')]").isNotEmpty());

        mockMvc.perform(post("/api/production-reminders/overtime/" + reminderId + "/no-adjustment")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"未来计划仍有需求\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handlingType").value("NO_ADJUSTMENT"));
        assertThat(planQuantity(futurePlanId)).as("无需调整不改计划").isEqualTo(4);
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions createOvertimeTask(LocalDate planDate,
                                                                                 long sourcePlanId, int quantity)
            throws Exception {
        return mockMvc.perform(post("/api/overtime-tasks")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderItemId":%d,"node":"MAKING","planDate":"%s","employeeId":%d,
                         "lines":[{"futurePlanId":%d,"quantity":%d}]}
                        """.formatted(orderItemId, planDate, makerId, sourcePlanId, quantity)));
    }

    private long createNormalPlan(LocalDate planDate, int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/production-plans")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planType":"NORMAL","orderItemId":%d,"node":"MAKING","planDate":"%s",
                                 "employeeId":%d,"quantity":%d}
                                """.formatted(orderItemId, planDate, makerId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return dataId(created);
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

    private long dataId(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("id").asLong();
    }

    /** 超额任务视图的业务 id 字段是 planId（它不是「生产计划」资源的常规视图）。 */
    private long dataPlanId(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("planId").asLong();
    }

    private int planQuantity(long planId) {
        var value = jdbcTemplate.queryForObject(
                "SELECT quantity FROM production_plans WHERE id = ?", Integer.class, planId);
        return value == null ? 0 : value;
    }

    private int makingPlanned() {
        var value = jdbcTemplate.queryForObject(
                "SELECT making_planned FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId);
        return value == null ? 0 : value;
    }

    private int reworkPending() {
        var value = jdbcTemplate.queryForObject(
                "SELECT rework_pending FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId);
        return value == null ? 0 : value;
    }

    private int remakePending() {
        var value = jdbcTemplate.queryForObject(
                "SELECT remake_pending FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                Integer.class, orderItemId);
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
