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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.3 计算接线契约：任务创建、任务详情与核验后的读取都必须返回同一份集中计算结果，
 * 返工不出现在正常工时与正常产能汇总里。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionTimeCalculationContractTest {

    private static final String USERNAME = "prod-time-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-12";
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
        var customerId = fixture.insertCustomer("TPT201", "TST-工时客户");
        var seamTypeId = fixture.insertSeamType("TST-工时缝边种类", 5);
        var productId = fixture.insertProduct("TPT301", "TST-工时商品", 5, 3, seamTypeId, "TST-工时缝边种类",
                10, 5);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        makerId = fixture.insertEmployee("TPT401", "TST-工时制作员", "ACTIVE", "MAKING");
        orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}})[0];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPT201')";
        jdbcTemplate.update("DELETE FROM rework_sources WHERE previous_source_id IS NOT NULL"
                + " AND order_id IN (" + testOrders + ")");
        for (var table : new String[]{"production_reminders", "production_quantity_returns", "scrap_records",
                "rework_sources", "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPT401')");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPT201')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPT401')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPT401'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPT301'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-工时缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPT201'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void taskVerificationAndWorkbenchUseSameResult() throws Exception {
        // 制作：单件标准分钟 5，计划 6 件 → 30 分钟；工作日 8 小时 × 0.75 = 360 分钟正常产能
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 6));
        var item = task.path("items").get(0);
        assertThat(item.path("standardMinutes").asInt()).isEqualTo(5);
        assertThat(item.path("estimatedMinutes").asLong()).isEqualTo(30);
        assertThat(item.path("normalMinutes").asLong()).isEqualTo(30);
        assertThat(item.path("capacityNotice").isNull()).isTrue();

        // 核验后再读任务：同一明细返回同一份计算结果
        var taskId = task.path("id").asLong();
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":6,"reworkQuantity":0,
                                 "scrapQuantity":0}]}
                                """.formatted(item.path("id").asLong())))
                .andExpect(status().isOk());
        var reread = objectMapper.readTree(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/production-tasks/" + taskId).cookie(sessionCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(reread.path("items").get(0).path("normalMinutes").asLong()).isEqualTo(30);
        assertThat(reread.path("items").get(0).path("standardMinutes").asInt()).isEqualTo(5);

        // 数据库只保存冻结快照，不保存派生工时（派生值一律读时计算）
        var columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'production_task_items'
                """, String.class);
        assertThat(columns).contains("standard_minutes", "estimated_minutes")
                .doesNotContain("normal_minutes", "normal_hours", "capacity_minutes");
    }

    @Test
    void reworkStaysOutOfNormalHoursAndCapacity() throws Exception {
        // 制作核验产生返工事实 → 显式来源 → 返工任务：返工不占正常工时与正常产能
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        mockMvc.perform(post("/api/production-tasks/" + task.path("id").asLong() + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":7,"reworkQuantity":3,
                                 "scrapQuantity":0}]}
                                """.formatted(task.path("items").get(0).path("id").asLong())))
                .andExpect(status().isOk());
        var verificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM production_verifications", Long.class);
        var source = mockMvc.perform(post("/api/rework-sources")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originVerificationId\":%d,\"quantity\":3,\"reason\":\"返工\"}"
                                .formatted(verificationId)))
                .andExpect(status().isCreated())
                .andReturn();
        var sourceId = objectMapper.readTree(source.getResponse().getContentAsString())
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
        JsonNode reworkItem = objectMapper.readTree(reworkTask.getResponse().getContentAsString())
                .path("data").path("items").get(0);
        assertThat(reworkItem.path("normalMinutes").asLong()).isZero();
        assertThat(reworkItem.path("estimatedMinutes").asLong()).isZero();
        assertThat(reworkItem.path("reworkMinutes").asLong()).isEqualTo(15);
        assertThat(reworkItem.path("dailyMaxCapacity").asInt()).isZero();
        // 正常产能占用只统计正常来源明细
        assertThat(reworkItem.path("usedNormalQuantity").asInt()).isZero();
    }
}
