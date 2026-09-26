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
 * 阶段五 5.16 幂等门禁：重复幂等键返回首次结果且不重复写事实；同键不同请求指纹返回 CONFLICT_IDEMPOTENCY。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionIdempotencyTest {

    private static final String USERNAME = "prod-idem-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-09";
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
        var customerId = fixture.insertCustomer("TPI001", "TST-幂等客户");
        var seamTypeId = fixture.insertSeamType("TST-幂等缝边种类", 5);
        var productId = fixture.insertProduct("TPI101", "TST-幂等商品", 5, 3, seamTypeId, "TST-幂等缝边种类",
                10, 5);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        makerId = fixture.insertEmployee("TPI201", "TST-幂等制作员", "ACTIVE", "MAKING");
        orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}})[0];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPI001')";
        for (var table : new String[]{"production_reminders", "production_quantity_returns", "scrap_records",
                "rework_sources", "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPI201')");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPI001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPI201')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPI201'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPI101'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-幂等缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPI001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private String createBody(int quantity) {
        return """
                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"NORMAL",
                 "items":[{"orderItemId":%d,"plannedQuantity":%d,"sourceType":"ORDER"}]}
                """.formatted(TASK_DATE, makerId, makingWorkTypeId, orderItemId, quantity);
    }

    @Test
    void replaysFirstResponseWithoutDuplicateTask() throws Exception {
        var key = ProductionFixture.key();
        var first = mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(4)))
                .andExpect(status().isCreated())
                .andReturn();
        var taskNo = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("taskNo").asText();

        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.taskNo").value(taskNo));
        assertThat(fixture.count("SELECT COUNT(*) FROM production_tasks")).isEqualTo(1);
        assertThat(fixture.count("SELECT COUNT(*) FROM production_task_items")).isEqualTo(1);
    }

    @Test
    void rejectsSameKeyWithDifferentFingerprint() throws Exception {
        var key = ProductionFixture.key();
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(4)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_IDEMPOTENCY"));
        assertThat(fixture.count("SELECT COUNT(*) FROM production_tasks")).isEqualTo(1);
    }

    @Test
    void replaysBatchVerificationWithoutDuplicateFacts() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 4));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();
        var body = """
                {"items":[{"taskItemId":%d,"qualifiedQuantity":4,"reworkQuantity":0,"scrapQuantity":0}]}
                """.formatted(itemId);
        var key = ProductionFixture.key();
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                            .cookie(sessionCookie)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
        assertThat(fixture.count("SELECT COUNT(*) FROM production_verifications")).isEqualTo(1);
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM fulfillment_entries WHERE entry_type = 'PRODUCTION_QUALIFIED'
                """)).isEqualTo(1);
    }
}
