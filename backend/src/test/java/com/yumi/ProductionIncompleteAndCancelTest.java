package com.yumi;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.13 未完成、提醒处理与明细取消：未完成回到普通待安排并生成提醒，
 * 「暂不安排」只追加处理事实与原因，取消只影响 PENDING 明细和未消费余额，已发货下限守卫生效。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionIncompleteAndCancelTest {

    private static final String USERNAME = "prod-cancel-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-10";
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

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        fixture = new ProductionFixture(mockMvc, jdbcTemplate, passwordEncoder, objectMapper);
        fixture.insertAdmin(USERNAME, PASSWORD);
        sessionCookie = fixture.login(USERNAME, PASSWORD);
        var customerId = fixture.insertCustomer("TPX001", "TST-取消客户");
        var seamTypeId = fixture.insertSeamType("TST-取消缝边种类", 5);
        var productId = fixture.insertProduct("TPX101", "TST-取消商品", 5, 3, seamTypeId, "TST-取消缝边种类",
                10, 5);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        makerId = fixture.insertEmployee("TPX201", "TST-取消制作员", "ACTIVE", "MAKING");
        orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}})[0];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPX001')";
        jdbcTemplate.update("DELETE FROM rework_sources WHERE previous_source_id IS NOT NULL"
                + " AND order_id IN (" + testOrders + ")");
        for (var table : new String[]{"production_reminders", "production_quantity_returns", "scrap_records",
                "rework_sources", "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPX201')");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPX001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPX201')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPX201'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPX101'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-取消缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPX001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private com.fasterxml.jackson.databind.JsonNode createTask(int planned) throws Exception {
        return fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, planned));
    }

    @Test
    void derivesIncompleteFromVerificationAndDefersWithReason() throws Exception {
        var task = createTask(10);
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":6,"reworkQuantity":0,
                                 "scrapQuantity":0}]}
                                """.formatted(itemId)))
                .andExpect(status().isOk());

        var reminders = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/production-reminders/incomplete")
                        .cookie(sessionCookie).param("orderItemId", String.valueOf(orderItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].quantity").value(4))
                .andExpect(jsonPath("$.data[0].reminderType").value("INCOMPLETE"))
                .andReturn();
        var reminderId = objectMapper.readTree(reminders.getResponse().getContentAsString())
                .path("data").get(0).path("id").asLong();

        // 暂不安排必须填写原因
        mockMvc.perform(post("/api/production-reminders/" + reminderId + "/defer")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));

        mockMvc.perform(post("/api/production-reminders/" + reminderId + "/defer")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"客户改期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HANDLED"))
                .andExpect(jsonPath("$.data.handlingType").value("DEFERRED"))
                .andExpect(jsonPath("$.data.reason").value("客户改期"));
        // 未完成需求保留：仍可重新安排 4 件
        assertThat(createTask(4).path("items").get(0).path("plannedQuantity").asInt()).isEqualTo(4);
    }

    @Test
    void cancelPendingItemReleasesOccupationAndKeepsHistory() throws Exception {
        var task = createTask(6);
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();
        var plannedBefore = fixture.count("""
                SELECT COALESCE(SUM(making_planned), 0) FROM order_item_fulfillment_balances
                WHERE order_item_id = ?
                """, orderItemId);
        assertThat(plannedBefore).isEqualTo(6);

        // 原因必填
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/items/" + itemId + "/cancel")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));

        mockMvc.perform(post("/api/production-tasks/" + taskId + "/items/" + itemId + "/cancel")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"设备故障\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.derivedStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.items[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.items[0].cancelReason").value("设备故障"));

        // 释放计划占用，取消原因与历史保留
        assertThat(fixture.count("""
                SELECT COALESCE(SUM(making_planned), 0) FROM order_item_fulfillment_balances
                WHERE order_item_id = ?
                """, orderItemId)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, cancelled_by, cancel_reason FROM production_task_items WHERE id = ?
                """, itemId).get("cancel_reason")).isEqualTo("设备故障");
        // 已取消明细不可重复取消
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/items/" + itemId + "/cancel")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"再次取消\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));
    }

    @Test
    void rejectsVerifiedItemCancel() throws Exception {
        var task = createTask(4);
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":4,"reworkQuantity":0,
                                 "scrapQuantity":0}]}
                                """.formatted(itemId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/production-tasks/" + taskId + "/items/" + itemId + "/cancel")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"想撤销\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM production_task_items WHERE id = ?", String.class, itemId))
                .isEqualTo("VERIFIED");
        assertThat(fixture.count("SELECT COUNT(*) FROM production_verifications")).isEqualTo(1);
    }

    @Test
    void rejectsCancelBelowShippedQuantity() throws Exception {
        var task = createTask(4);
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();
        // 模拟已发货投影：取消后该工序剩余有效覆盖为 0，低于已发货数量
        jdbcTemplate.update("""
                UPDATE order_item_fulfillment_balances SET shipped_quantity = 2 WHERE order_item_id = ?
                """, orderItemId);

        mockMvc.perform(post("/api/production-tasks/" + taskId + "/items/" + itemId + "/cancel")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"取消剩余需求\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_BELOW_SHIPPED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM production_task_items WHERE id = ?", String.class, itemId))
                .isEqualTo("PENDING");
    }

    @Test
    void cancelReworkItemReturnsSourceBalance() throws Exception {
        var task = createTask(10);
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":7,"reworkQuantity":3,
                                 "scrapQuantity":0}]}
                                """.formatted(itemId)))
                .andExpect(status().isOk());
        var verificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM production_verifications", Long.class);
        var sourceResponse = mockMvc.perform(post("/api/rework-sources")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originVerificationId\":%d,\"quantity\":3,\"reason\":\"返工\"}"
                                .formatted(verificationId)))
                .andExpect(status().isCreated())
                .andReturn();
        var sourceId = objectMapper.readTree(sourceResponse.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        var reworkTask = mockMvc.perform(post("/api/rework-sources/" + sourceId + "/tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"items":[{"plannedQuantity":3}]}
                                """.formatted(TASK_DATE, makerId)))
                .andExpect(status().isCreated())
                .andReturn();
        var reworkData = objectMapper.readTree(reworkTask.getResponse().getContentAsString()).path("data");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT arranged_quantity FROM rework_sources WHERE id = ?", Integer.class, sourceId))
                .isEqualTo(3);

        // 取消未执行的返工明细：来源余额恢复，可重新安排
        mockMvc.perform(post("/api/production-tasks/" + reworkData.path("id").asLong() + "/items/"
                        + reworkData.path("items").get(0).path("id").asLong() + "/cancel")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"改到明天\"}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT arranged_quantity FROM rework_sources WHERE id = ?", Integer.class, sourceId))
                .isZero();
    }
}
