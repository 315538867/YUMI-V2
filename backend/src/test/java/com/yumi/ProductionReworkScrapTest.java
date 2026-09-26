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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.9–5.12 返工来源与报废回转：核验只记录返工事实，来源必须显式创建且可多轮串联；
 * 报废写不可变事实并一对一生成同工序回转，后续只能用 NORMAL 明细消费，不存在任何替代类型。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionReworkScrapTest {

    private static final String USERNAME = "prod-rework-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-07";
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
    private long customerId;
    private long orderItemId;
    private long makerId;
    private long packerId;
    private long makingWorkTypeId;
    private long packingWorkTypeId;
    private long productId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        fixture = new ProductionFixture(mockMvc, jdbcTemplate, passwordEncoder, objectMapper);
        fixture.insertAdmin(USERNAME, PASSWORD);
        sessionCookie = fixture.login(USERNAME, PASSWORD);
        customerId = fixture.insertCustomer("TPW001", "TST-返工客户");
        var seamTypeId = fixture.insertSeamType("TST-返工缝边种类", 5);
        productId = fixture.insertProduct("TPW101", "TST-返工商品", 5, 3, seamTypeId, "TST-返工缝边种类", 10, 5);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        packingWorkTypeId = fixture.workTypeId("PACKING_BAG");
        makerId = fixture.insertEmployee("TPW201", "TST-返工制作员", "ACTIVE", "MAKING");
        packerId = fixture.insertEmployee("TPW202", "TST-返工包装员", "ACTIVE", "PACKING_BAG");
        orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}})[0];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPW001')";
        // 返工来源是自引用父子链：先删子来源再删父来源，否则外键约束会阻止清理
        jdbcTemplate.update("DELETE FROM rework_sources WHERE previous_source_id IS NOT NULL"
                + " AND order_id IN (" + testOrders + ")");
        for (var table : new String[]{"production_reminders", "production_quantity_returns", "scrap_records",
                "rework_sources", "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TPW201', 'TPW202'))");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPW001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TPW201', 'TPW202'))");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no IN ('TPW201', 'TPW202')");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPW101'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-返工缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPW001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private JsonNode verify(long taskId, long itemId, int qualified, int rework, int scrap) throws Exception {
        var response = mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":%d,"reworkQuantity":%d,
                                 "scrapQuantity":%d}]}
                                """.formatted(itemId, qualified, rework, scrap)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).path("data");
    }

    /** 制作计划 10 件并核验：返回核验事实 id。 */
    private long verifyMaking(int qualified, int rework, int scrap) throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();
        verify(taskId, itemId, qualified, rework, scrap);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_verifications WHERE task_item_id = ?", Long.class, itemId);
    }

    private JsonNode createSource(long verificationId, int quantity, Long previousSourceId) throws Exception {
        var previous = previousSourceId == null ? "" : ",\"previousSourceId\":" + previousSourceId;
        var response = mockMvc.perform(post("/api/rework-sources")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originVerificationId\":%d,\"quantity\":%d,\"reason\":\"TST 返工\"%s}"
                                .formatted(verificationId, quantity, previous)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).path("data");
    }

    private int countSources() {
        return fixture.count("SELECT COUNT(*) FROM rework_sources WHERE order_item_id = ?", orderItemId);
    }

    private int reworkPending() {
        return fixture.count(
                "SELECT rework_pending FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                orderItemId);
    }

    @Test
    void createsExplicitReworkSourceAndReworkTask() throws Exception {
        var verificationId = verifyMaking(7, 3, 0);
        // 核验只记录返工事实：不自动创建可安排来源，但进入「待安排返工」投影
        assertThat(countSources()).isZero();
        assertThat(reworkPending()).isEqualTo(3);

        var source = createSource(verificationId, 2, null);
        assertThat(source.path("node").asText()).isEqualTo("MAKING");
        assertThat(source.path("roundNo").asInt()).isEqualTo(1);
        assertThat(source.path("availableQuantity").asInt()).isEqualTo(2);
        // 来源创建即从待安排转入来源额度
        assertThat(reworkPending()).isEqualTo(1);

        var task = mockMvc.perform(post("/api/rework-sources/" + source.path("id").asLong() + "/tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"note":"TST 返工任务",
                                 "items":[{"plannedQuantity":2}]}
                                """.formatted(TASK_DATE, makerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.taskType").value("REWORK"))
                .andReturn();
        var taskData = objectMapper.readTree(task.getResponse().getContentAsString()).path("data");
        assertThat(taskData.path("items").get(0).path("node").asText()).isEqualTo("MAKING");
        assertThat(taskData.path("items").get(0).path("sourceType").asText()).isEqualTo("REWORK_SOURCE");
        // 返工不占正常工时：估算分钟为 0
        assertThat(taskData.path("items").get(0).path("normalMinutes").asLong()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT arranged_quantity FROM rework_sources WHERE id = ?", Integer.class,
                source.path("id").asLong())).isEqualTo(2);
    }

    @Test
    void rejectsSourceBeyondUnallocatedFactAndWrongNode() throws Exception {
        var verificationId = verifyMaking(7, 3, 0);
        createSource(verificationId, 2, null);
        // 返工事实共 3，已建来源 2，剩余 1
        mockMvc.perform(post("/api/rework-sources")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originVerificationId\":%d,\"quantity\":2,\"reason\":\"超量\"}"
                                .formatted(verificationId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));
        assertThat(countSources()).isEqualTo(1);

        // 返工必须在来源发生工序内：用捏毛装袋工种提交制作来源的返工明细被拒
        var source = countSources();
        var sourceId = jdbcTemplate.queryForObject(
                "SELECT id FROM rework_sources WHERE order_item_id = ? ORDER BY id", Long.class, orderItemId);
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"REWORK",
                                 "items":[{"orderItemId":%d,"plannedQuantity":1,
                                 "sourceType":"REWORK_SOURCE","sourceId":%d}]}
                                """.formatted(TASK_DATE, packerId, packingWorkTypeId, orderItemId, sourceId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INVALID"));
        assertThat(source).isEqualTo(1);
    }

    @Test
    void createsTwoExplicitReworkRoundsAndKeepsCapacityIsolated() throws Exception {
        var firstVerification = verifyMaking(7, 3, 0);
        var firstSource = createSource(firstVerification, 3, null);
        var reworkTask = mockMvc.perform(post("/api/rework-sources/" + firstSource.path("id").asLong() + "/tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"items":[{"plannedQuantity":3}]}
                                """.formatted(TASK_DATE, makerId)))
                .andExpect(status().isCreated())
                .andReturn();
        var reworkTaskData = objectMapper.readTree(reworkTask.getResponse().getContentAsString()).path("data");
        var reworkItemId = reworkTaskData.path("items").get(0).path("id").asLong();
        var reworkTaskId = reworkTaskData.path("id").asLong();
        // 返工任务不占产品日产能
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(planned_quantity), 0) FROM production_task_items
                WHERE product_id = ? AND source_type IN ('ORDER', 'QUANTITY_RETURN')
                """, Integer.class, productId)).isEqualTo(10);

        // 返工再次返工：第二轮来源必须基于新的返工事实显式创建
        verify(reworkTaskId, reworkItemId, 1, 2, 0);
        var secondVerification = jdbcTemplate.queryForObject(
                "SELECT id FROM production_verifications WHERE task_item_id = ?", Long.class, reworkItemId);
        var secondSource = createSource(secondVerification, 2, firstSource.path("id").asLong());
        assertThat(secondSource.path("roundNo").asInt()).isEqualTo(2);
        assertThat(secondSource.path("previousSourceId").asLong()).isEqualTo(firstSource.path("id").asLong());
        assertThat(jdbcTemplate.queryForList("""
                SELECT round_no FROM rework_sources WHERE order_item_id = ? ORDER BY round_no
                """, Integer.class, orderItemId)).containsExactly(1, 2);
    }

    @Test
    void returnsReworkIncompleteToSourceBalance() throws Exception {
        var verificationId = verifyMaking(7, 3, 0);
        var source = createSource(verificationId, 3, null);
        var task = mockMvc.perform(post("/api/rework-sources/" + source.path("id").asLong() + "/tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"items":[{"plannedQuantity":3}]}
                                """.formatted(TASK_DATE, makerId)))
                .andExpect(status().isCreated())
                .andReturn();
        var data = objectMapper.readTree(task.getResponse().getContentAsString()).path("data");
        var taskId = data.path("id").asLong();
        var itemId = data.path("items").get(0).path("id").asLong();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT arranged_quantity FROM rework_sources WHERE id = ?", Integer.class,
                source.path("id").asLong())).isEqualTo(3);

        // 只完成 1 件：未完成 2 件退回来源余额，可重新安排；不产生订单侧未完成提醒
        var result = verify(taskId, itemId, 1, 0, 0);
        assertThat(result.path("items").get(0).path("incompleteQuantity").asInt()).isEqualTo(2);
        assertThat(result.path("items").get(0).path("incompleteReminderId").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT arranged_quantity FROM rework_sources WHERE id = ?", Integer.class,
                source.path("id").asLong())).isEqualTo(1);
        assertThat(fixture.count("SELECT COUNT(*) FROM production_reminders")).isZero();
    }

    @Test
    void writesScrapAndOneReturnAtomically() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();
        verify(taskId, itemId, 8, 0, 2);

        var scrap = jdbcTemplate.queryForMap("""
                SELECT id, node, scrap_quantity, task_item_id FROM scrap_records WHERE task_item_id = ?
                """, itemId);
        assertThat(scrap.get("node")).isEqualTo("MAKING");
        assertThat(((Number) scrap.get("scrap_quantity")).intValue()).isEqualTo(2);
        var returns = jdbcTemplate.queryForMap("""
                SELECT id, returned_quantity, allocated_quantity, node FROM production_quantity_returns
                WHERE scrap_record_id = ?
                """, scrap.get("id"));
        assertThat(((Number) returns.get("returned_quantity")).intValue()).isEqualTo(2);
        assertThat(((Number) returns.get("allocated_quantity")).intValue()).isZero();
        assertThat(returns.get("node")).isEqualTo("MAKING");
        // 报废不增加订单需求、不产生上游合格事实
        assertThat(fixture.count("""
                SELECT required_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, orderItemId)).isEqualTo(10);
        // 合格 8 正常流入捏毛装袋；报废不产生任何额外合格事实
        assertThat(fixture.count("""
                SELECT COALESCE(SUM(quantity), 0) FROM fulfillment_entries
                WHERE order_item_id = ? AND entry_type = 'PRODUCTION_QUALIFIED' AND node = 'PACKING_BAG'
                """, orderItemId)).isEqualTo(8);
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM fulfillment_entries WHERE entry_type = 'PRODUCTION_QUALIFIED'
                """)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'remake_sources'
                """, String.class)).isEmpty();
    }

    @Test
    void reusesReturnOnlyThroughNormalTask() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        verify(task.path("id").asLong(), task.path("items").get(0).path("id").asLong(), 8, 0, 2);
        var returnId = jdbcTemplate.queryForObject(
                "SELECT id FROM production_quantity_returns", Long.class);

        // 后续重新生产必须创建 NORMAL 明细，并占用同工序回转余额
        var replanned = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                """
                {"orderItemId":%d,"plannedQuantity":2,"sourceType":"QUANTITY_RETURN","sourceId":%d}
                """.formatted(orderItemId, returnId));
        assertThat(replanned.path("items").get(0).path("sourceType").asText()).isEqualTo("QUANTITY_RETURN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT allocated_quantity FROM production_quantity_returns WHERE id = ?", Integer.class,
                returnId)).isEqualTo(2);
    }
}
