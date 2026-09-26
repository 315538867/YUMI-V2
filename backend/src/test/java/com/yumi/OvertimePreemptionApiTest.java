package com.yumi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.support.ProductionFixture;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.14 超额预占：只在执行当天创建、来源只能是未来日期的 NORMAL 明细、
 * 预占不修改未来原计划；只有合格数量产生未来计划调整提醒，返工与报废不减少未来计划。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OvertimePreemptionApiTest {

    private static final String USERNAME = "overtime-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String ORDER_DATE = "2026-09-24";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    ObjectMapper objectMapper;

    private ProductionFixture fixture;
    private Cookie sessionCookie;
    private long orderItemId;
    private long makerId;
    private long makingWorkTypeId;
    private long futureItemId;
    private final LocalDate today = LocalDate.now();
    private final LocalDate futureDate = LocalDate.now().plusDays(1);

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        fixture = new ProductionFixture(mockMvc, jdbcTemplate, passwordEncoder, objectMapper);
        fixture.insertAdmin(USERNAME, PASSWORD);
        sessionCookie = fixture.login(USERNAME, PASSWORD);
        var customerId = fixture.insertCustomer("TPO001", "TST-超额客户");
        var seamTypeId = fixture.insertSeamType("TST-超额缝边种类", 5);
        var productId = fixture.insertProduct("TPO101", "TST-超额商品", 5, 3, seamTypeId, "TST-超额缝边种类",
                10, 5);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        makerId = fixture.insertEmployee("TPO201", "TST-超额制作员", "ACTIVE", "MAKING");
        orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}})[0];
        // 未来日期的正常任务明细：超额预占的唯一合法来源
        var future = fixture.createTask(sessionCookie, futureDate.toString(), makerId, makingWorkTypeId,
                "NORMAL", fixture.orderSourceItem(orderItemId, 6));
        futureItemId = future.path("items").get(0).path("id").asLong();
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPO001')";
        // 外键顺序：提醒引用预占，预占引用未来任务明细 → 先删提醒，再删预占/超额，最后删生产任务
        jdbcTemplate.update("DELETE FROM production_reminders WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM overtime_preemptions WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM overtime_task_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM overtime_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPO201')");
        for (var table : new String[]{"production_quantity_returns", "scrap_records", "rework_sources",
                "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPO201')");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPO001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPO201')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPO201'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPO101'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-超额缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPO001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private JsonNode createOvertime(LocalDate taskDate, long sourceItemId, int quantity) throws Exception {
        var response = mockMvc.perform(post("/api/overtime-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"note":"TST 超额",
                                 "items":[{"futureTaskItemId":%d,"plannedQuantity":%d}]}
                                """.formatted(taskDate, makerId, sourceItemId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).path("data");
    }

    private int futurePlannedQuantity() {
        return fixture.count("SELECT planned_quantity FROM production_task_items WHERE id = ?", futureItemId);
    }

    @Test
    void acceptsOnlyFutureNormalItems() throws Exception {
        var created = createOvertime(today, futureItemId, 4);
        assertThat(created.path("taskNo").asText()).matches("OT\\d{6}");
        // 预占不修改未来明细原计划数量，也不产生任何工序流入
        assertThat(futurePlannedQuantity()).isEqualTo(6);
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM fulfillment_entries WHERE entry_type = 'PRODUCTION_QUALIFIED'
                """)).isZero();
        var preemption = jdbcTemplate.queryForMap("""
                SELECT preempted_quantity, status, future_task_item_id FROM overtime_preemptions
                """);
        assertThat(((Number) preemption.get("preempted_quantity")).intValue()).isEqualTo(4);
        assertThat(preemption.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) preemption.get("future_task_item_id")).longValue()).isEqualTo(futureItemId);
        // 创建即生成「超额待核验」提醒
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM production_reminders
                WHERE reminder_type = 'OVERTIME_PENDING_VERIFY' AND status = 'OPEN'
                """)).isEqualTo(1);
    }

    @Test
    void rejectsTodayOrPastSource() throws Exception {
        // 超额只能在执行当天创建
        mockMvc.perform(post("/api/overtime-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,
                                 "items":[{"futureTaskItemId":%d,"plannedQuantity":2}]}
                                """.formatted(futureDate, makerId, futureItemId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OVERTIME_DATE_INVALID"));

        // 来源必须是未来日期的正常明细：当天明细被拒
        var todayTask = fixture.createTask(sessionCookie, today.toString(), makerId, makingWorkTypeId,
                "NORMAL", fixture.orderSourceItem(orderItemId, 2));
        var todayItemId = todayTask.path("items").get(0).path("id").asLong();
        mockMvc.perform(post("/api/overtime-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,
                                 "items":[{"futureTaskItemId":%d,"plannedQuantity":2}]}
                                """.formatted(today, makerId, todayItemId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
        assertThat(fixture.count("SELECT COUNT(*) FROM overtime_tasks")).isZero();
    }

    @Test
    void rejectsReservationBeyondFutureBalance() throws Exception {
        createOvertime(today, futureItemId, 4);
        // 未来计划 6，已预占 4，再预占 3 超过可预占余额
        mockMvc.perform(post("/api/overtime-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,
                                 "items":[{"futureTaskItemId":%d,"plannedQuantity":3}]}
                                """.formatted(today, makerId, futureItemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERTIME_RESERVATION_EXCEEDED"));
        assertThat(futurePlannedQuantity()).isEqualTo(6);
        assertThat(fixture.count("SELECT COUNT(*) FROM overtime_preemptions")).isEqualTo(1);
    }

    @Test
    void qualifiedQuantityCreatesPlanAdjustmentReminder() throws Exception {
        var created = createOvertime(today, futureItemId, 4);
        var overtimeItemId = created.path("items").get(0).path("id").asLong();
        mockMvc.perform(post("/api/overtime-tasks/" + created.path("id").asLong() + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"overtimeItemId":%d,"completedQuantity":4,
                                 "qualifiedQuantity":4,"reworkQuantity":0,"scrapQuantity":0}]}
                                """.formatted(overtimeItemId)))
                .andExpect(status().isOk());

        // 预占释放；合格 4 件形成未来计划调整提醒，但未来原计划数量不变
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM overtime_preemptions", String.class)).isEqualTo("RELEASED");
        var adjustment = jdbcTemplate.queryForMap("""
                SELECT quantity, future_task_item_id, status FROM production_reminders
                WHERE reminder_type = 'PLAN_ADJUSTMENT'
                """);
        assertThat(((Number) adjustment.get("quantity")).intValue()).isEqualTo(4);
        assertThat(((Number) adjustment.get("future_task_item_id")).longValue()).isEqualTo(futureItemId);
        assertThat(adjustment.get("status")).isEqualTo("OPEN");
        assertThat(futurePlannedQuantity()).isEqualTo(6);
    }

    @Test
    void reworkAndScrapDoNotCreateAdjustment() throws Exception {
        var created = createOvertime(today, futureItemId, 2);
        var overtimeItemId = created.path("items").get(0).path("id").asLong();
        mockMvc.perform(post("/api/overtime-tasks/" + created.path("id").asLong() + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"overtimeItemId":%d,"completedQuantity":2,
                                 "qualifiedQuantity":0,"reworkQuantity":1,"scrapQuantity":1}]}
                                """.formatted(overtimeItemId)))
                .andExpect(status().isOk());

        // 零合格：预占释放、待核验提醒转为无需调整，且不产生任何未来计划减少建议
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM overtime_preemptions", String.class)).isEqualTo("RELEASED");
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM production_reminders WHERE reminder_type = 'PLAN_ADJUSTMENT'
                """)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT handling_type FROM production_reminders WHERE reminder_type = 'OVERTIME_PENDING_VERIFY'
                """, String.class)).isEqualTo("NO_ADJUSTMENT");
        assertThat(futurePlannedQuantity()).isEqualTo(6);
    }
}
