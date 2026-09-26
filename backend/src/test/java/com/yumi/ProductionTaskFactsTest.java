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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.18 事实追溯：任务详情返回逐明细核验事实，事实时间线按
 * `factTime ASC, factType ASC, factId ASC` 覆盖计划、核验、返工事实与来源、报废、数量回转、
 * 合格流转、未完成与取消，并保留来源 id、父核验、操作人、原因与数量。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionTaskFactsTest {

    private static final String USERNAME = "prod-facts-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-13";
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
    private long secondItemId;
    private long makerId;
    private long makingWorkTypeId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        fixture = new ProductionFixture(mockMvc, jdbcTemplate, passwordEncoder, objectMapper);
        fixture.insertAdmin(USERNAME, PASSWORD);
        sessionCookie = fixture.login(USERNAME, PASSWORD);
        var customerId = fixture.insertCustomer("TPF001", "TST-追溯客户");
        var seamTypeId = fixture.insertSeamType("TST-追溯缝边种类", 5);
        var productId = fixture.insertProduct("TPF101", "TST-追溯商品", 5, 3, seamTypeId, "TST-追溯缝边种类",
                10, 5);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        makerId = fixture.insertEmployee("TPF201", "TST-追溯制作员", "ACTIVE", "MAKING");
        var items = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}, {(int) productId, 4, 0}});
        orderItemId = items[0];
        secondItemId = items[1];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPF001')";
        jdbcTemplate.update("DELETE FROM rework_sources WHERE previous_source_id IS NOT NULL"
                + " AND order_id IN (" + testOrders + ")");
        for (var table : new String[]{"production_reminders", "production_quantity_returns", "scrap_records",
                "rework_sources", "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPF201')");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPF001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPF201')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPF201'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPF101'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-追溯缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPF001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private List<String> factTypes(long taskId) throws Exception {
        var response = mockMvc.perform(get("/api/production-tasks/" + taskId + "/facts").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();
        var facts = objectMapper.readTree(response.getResponse().getContentAsString()).path("data");
        var types = new ArrayList<String>();
        facts.forEach(fact -> types.add(fact.path("factType").asText()));
        return types;
    }

    private JsonNode factOfType(long taskId, String type) throws Exception {
        var response = mockMvc.perform(get("/api/production-tasks/" + taskId + "/facts").cookie(sessionCookie))
                .andReturn();
        for (var fact : objectMapper.readTree(response.getResponse().getContentAsString()).path("data")) {
            if (type.equals(fact.path("factType").asText())) {
                return fact;
            }
        }
        throw new AssertionError("时间线缺少事实类型 " + type);
    }

    @Test
    void returnsVerificationFactsPerItemAndOrderedTimeline() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10) + "," + fixture.orderSourceItem(secondItemId, 4));
        var taskId = task.path("id").asLong();
        var firstItemId = task.path("items").get(0).path("id").asLong();
        var secondTaskItemId = task.path("items").get(1).path("id").asLong();

        // 第一条：合格 6 + 返工 2 + 报废 1 → 完成 9，未完成 1
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":6,"reworkQuantity":2,
                                 "scrapQuantity":1}]}
                                """.formatted(firstItemId)))
                .andExpect(status().isOk());
        var verificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM production_verifications WHERE task_item_id = ?", Long.class, firstItemId);
        // 返工来源显式创建
        mockMvc.perform(post("/api/rework-sources")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originVerificationId\":%d,\"quantity\":2,\"reason\":\"追溯返工\"}"
                                .formatted(verificationId)))
                .andExpect(status().isCreated());
        // 第二条：取消
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/items/" + secondTaskItemId + "/cancel")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"追溯取消\"}"))
                .andExpect(status().isOk());

        // 明细视图带逐明细核验事实
        mockMvc.perform(get("/api/production-tasks/" + taskId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].verification.qualifiedQuantity").value(6))
                .andExpect(jsonPath("$.data.items[0].verification.reworkQuantity").value(2))
                .andExpect(jsonPath("$.data.items[0].verification.scrapQuantity").value(1))
                .andExpect(jsonPath("$.data.items[0].verification.incompleteQuantity").value(1))
                .andExpect(jsonPath("$.data.items[0].verification.verifiedBy").value(USERNAME))
                .andExpect(jsonPath("$.data.items[1].verification").doesNotExist());

        var types = factTypes(taskId);
        assertThat(types).contains("PLAN", "VERIFICATION", "REWORK_FACT", "REWORK_SOURCE", "SCRAP",
                "QUANTITY_RETURN", "QUALIFIED_FLOW", "INCOMPLETE", "CANCEL");
        // 稳定排序：factTime 非降序（同秒内按类型与 id 兜底）
        var facts = mockMvc.perform(get("/api/production-tasks/" + taskId + "/facts").cookie(sessionCookie))
                .andReturn().getResponse().getContentAsString();
        var times = new ArrayList<String>();
        objectMapper.readTree(facts).path("data").forEach(f -> times.add(f.path("factTime").asText()));
        assertThat(times).isSorted();

        // 关键事实保留数量、来源与原因
        assertThat(factOfType(taskId, "PLAN").path("quantity").asInt()).isEqualTo(10);
        assertThat(factOfType(taskId, "REWORK_FACT").path("quantity").asInt()).isEqualTo(2);
        assertThat(factOfType(taskId, "REWORK_FACT").path("referenceId").asLong()).isEqualTo(verificationId);
        assertThat(factOfType(taskId, "SCRAP").path("quantity").asInt()).isEqualTo(1);
        assertThat(factOfType(taskId, "QUANTITY_RETURN").path("quantity").asInt()).isEqualTo(1);
        assertThat(factOfType(taskId, "INCOMPLETE").path("quantity").asInt()).isEqualTo(1);
        assertThat(factOfType(taskId, "CANCEL").path("reason").asText()).isEqualTo("追溯取消");
        assertThat(factOfType(taskId, "REWORK_SOURCE").path("reason").asText()).isEqualTo("追溯返工");
        assertThat(factOfType(taskId, "QUALIFIED_FLOW").path("node").asText()).isEqualTo("PACKING_BAG");
    }
}
