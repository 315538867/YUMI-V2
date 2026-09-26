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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.2/5.4/5.5 生产任务 API：一个任务头承载多个订单、多个产品与多条明细；
 * 任务头不保存数量汇总；产品日产能是硬约束；REMAKE 无法进入服务层成功分支；任一明细失败整批回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionTaskApiTest {

    private static final String USERNAME = "prod-task-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-05";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    ObjectMapper objectMapper;

    private Cookie sessionCookie;
    private long customerId;
    private long makerId;
    private long resignedId;
    private long packerId;
    private long makingWorkTypeId;
    private long packingWorkTypeId;
    private long orderItemA;
    private long orderItemB;
    private long orderItemC;
    private long productA;
    private long productB;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, contact, phone, default_recipient,
                    default_recipient_phone, default_region, default_address, version, created_at, updated_at)
                VALUES ('TPT001', 'TST-任务客户', '联系人', '13900000000', '默认收货人', '13900000001',
                    '华南', '默认收货地址', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TPT001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO seam_types (name, std_minutes, version, created_at, updated_at)
                VALUES ('TST-任务缝边种类', 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        var seamTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM seam_types WHERE name = 'TST-任务缝边种类'", Long.class);
        productA = insertProduct("TPS101", "TST-任务商品甲", 5, 3, seamTypeId, 10, 5);
        productB = insertProduct("TPS102", "TST-任务商品乙", 12, 3, seamTypeId, 4, 5);

        makingWorkTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM work_types WHERE code = 'MAKING'", Long.class);
        packingWorkTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM work_types WHERE code = 'PACKING_BAG'", Long.class);
        makerId = insertEmployee("TST-制作员工", "TPE101", "ACTIVE", makingWorkTypeId);
        resignedId = insertEmployee("TST-离职员工", "TPE102", "RESIGNED", makingWorkTypeId);
        packerId = insertEmployee("TST-包装员工", "TPE103", "ACTIVE", packingWorkTypeId);

        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");

        // 两个已确认订单：订单一含甲商品（缝边 4）与乙商品，订单二含甲商品
        var firstOrder = confirmOrder(new int[][]{{(int) productA, 10, 4}, {(int) productB, 6, 0}});
        orderItemA = firstOrder[0];
        orderItemB = firstOrder[1];
        orderItemC = confirmOrder(new int[][]{{(int) productA, 8, 0}})[0];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPT001')";
        jdbcTemplate.update("DELETE FROM production_reminders WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM production_quantity_returns WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM scrap_records WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM rework_sources WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM production_verifications WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM production_task_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN (?, ?, ?)", makerId, resignedId,
                packerId);
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPT001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN (?, ?, ?)", makerId, resignedId,
                packerId);
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no IN ('TPE101', 'TPE102', 'TPE103')");
        jdbcTemplate.update("DELETE FROM products WHERE product_no IN ('TPS101', 'TPS102')");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-任务缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPT001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private long insertProduct(String no, String name, int starMinutes, int packagingMinutes, long seamTypeId,
                               int moldQuantity, int dailyBatchLimit) {
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    packaging_tier_name, packaging_std_minutes, sale_price, weight_g, total_cost,
                    seam_type_id, seam_type_name, seam_std_minutes, seam_unit_cost, seam_fee,
                    mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', ?,
                    'TST-任务档位', ?, 25.0000, 100, 18.1200,
                    ?, 'TST-任务缝边种类', 5, 1.2500, 2.0000, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, no, name, starMinutes, packagingMinutes, seamTypeId, moldQuantity, dailyBatchLimit);
        return jdbcTemplate.queryForObject("SELECT id FROM products WHERE product_no = ?", Long.class, no);
    }

    private long insertEmployee(String name, String no, String status, long workTypeId) {
        jdbcTemplate.update("""
                INSERT INTO employees (employee_no, name, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, no, name, status);
        var id = jdbcTemplate.queryForObject("SELECT id FROM employees WHERE employee_no = ?", Long.class, no);
        jdbcTemplate.update("""
                INSERT INTO employee_work_types (employee_id, work_type_id, created_at)
                VALUES (?, ?, UTC_TIMESTAMP(6))
                """, id, workTypeId);
        return id;
    }

    private long[] confirmOrder(int[][] items) throws Exception {
        var builder = new StringBuilder();
        for (var item : items) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append("{\"productId\":").append(item[0]).append(",\"quantity\":").append(item[1])
                    .append(",\"seamQuantity\":").append(item[2]).append('}');
        }
        var created = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"orderDate\":\"2026-09-24\",\"items\":["
                                + builder + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        var orderId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        mockMvc.perform(post("/api/orders/" + orderId + "/confirm")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        return jdbcTemplate.queryForList(
                        "SELECT id FROM order_items WHERE order_id = ? ORDER BY line_no", Long.class, orderId)
                .stream().mapToLong(Long::longValue).toArray();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private String createBody(String taskType, String itemsJson) {
        return """
                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"%s","note":"TST 任务",
                 "items":[%s]}
                """.formatted(TASK_DATE, makerId, makingWorkTypeId, taskType, itemsJson);
    }

    private JsonNode createTask(String body) throws Exception {
        var response = mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).path("data");
    }

    private int countTasks() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM production_tasks", Integer.class);
    }

    @Test
    void acceptsMultipleOrdersAndProducts() throws Exception {
        var created = createTask(createBody("NORMAL", """
                {"orderItemId":%d,"plannedQuantity":6,"sourceType":"ORDER"},
                {"orderItemId":%d,"plannedQuantity":4,"sourceType":"ORDER"},
                {"orderItemId":%d,"plannedQuantity":3,"sourceType":"ORDER"}
                """.formatted(orderItemA, orderItemB, orderItemC)));

        assertThat(created.path("taskNo").asText()).matches("PT\\d{6}");
        assertThat(created.path("items")).hasSize(3);
        assertThat(created.path("derivedStatus").asText()).isEqualTo("SCHEDULED");
        // 任务头不保存数量汇总
        var headerColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'production_tasks'
                """, String.class);
        assertThat(headerColumns).doesNotContain("quantity", "planned_quantity", "completed_quantity");
        // 三条明细分别保留订单、产品与工序快照
        var items = created.path("items");
        assertThat(items.get(0).path("orderItemId").asLong()).isEqualTo(orderItemA);
        assertThat(items.get(0).path("productId").asLong()).isEqualTo(productA);
        assertThat(items.get(0).path("node").asText()).isEqualTo("MAKING");
        assertThat(items.get(1).path("productId").asLong()).isEqualTo(productB);
        assertThat(items.get(2).path("standardMinutes").asInt()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM production_task_items", Integer.class)).isEqualTo(3);
    }

    @Test
    void returnsSnapshotsAndCapacityNotice() throws Exception {
        // 商品甲：制作标准分钟 5，日最大产能 10 × 5 = 50；计划 6 件 → 30 分钟，未超产能
        var created = createTask(createBody("NORMAL",
                "{\"orderItemId\":%d,\"plannedQuantity\":6,\"sourceType\":\"ORDER\"}".formatted(orderItemA)));
        var item = created.path("items").get(0);
        assertThat(item.path("standardMinutes").asInt()).isEqualTo(5);
        assertThat(item.path("estimatedMinutes").asLong()).isEqualTo(30);
        assertThat(item.path("normalMinutes").asLong()).isEqualTo(30);
        assertThat(item.path("dailyMaxCapacity").asInt()).isEqualTo(50);
        assertThat(item.path("usedNormalQuantity").asInt()).isEqualTo(6);
        assertThat(item.path("remainingNormalQuantity").asInt()).isEqualTo(44);
        assertThat(item.path("actualInflow").asInt()).isEqualTo(10);
        // 首道制作的正常流入就是订单实际制作缺口：计划本身可执行，不等待上游
        assertThat(item.path("executableQuantity").asInt()).isEqualTo(6);
        assertThat(item.path("waitingUpstream").asBoolean()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_entries WHERE entry_type = 'PRODUCTION_QUALIFIED'",
                Integer.class)).isZero();

        // 下游工序没有上游合格流入时只能等待上游：计划可建，但可执行量为 0
        var downstream = createTask("""
                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"NORMAL",
                 "items":[{"orderItemId":%d,"plannedQuantity":5,"sourceType":"ORDER"}]}
                """.formatted(TASK_DATE, packerId, packingWorkTypeId, orderItemA));
        var downstreamItem = downstream.path("items").get(0);
        assertThat(downstreamItem.path("node").asText()).isEqualTo("PACKING_BAG");
        assertThat(downstreamItem.path("actualInflow").asInt()).isZero();
        assertThat(downstreamItem.path("executableQuantity").asInt()).isZero();
        assertThat(downstreamItem.path("waitingUpstream").asBoolean()).isTrue();
    }

    @Test
    void rejectsDraftOrder() throws Exception {
        var draft = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"orderDate\":\"2026-09-24\",\"items\":"
                                + "[{\"productId\":" + productA + ",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        var draftOrderId = objectMapper.readTree(draft.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        var draftItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ?", Long.class, draftOrderId);

        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("NORMAL",
                                "{\"orderItemId\":%d,\"plannedQuantity\":2,\"sourceType\":\"ORDER\"}"
                                        .formatted(draftItemId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='items[0].orderItemId')]").isNotEmpty());
        assertThat(countTasks()).isZero();
    }

    @Test
    void rejectsNonPositiveQuantityAndInvalidItem() throws Exception {
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("NORMAL",
                                "{\"orderItemId\":%d,\"plannedQuantity\":0,\"sourceType\":\"ORDER\"}"
                                        .formatted(orderItemA))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='items[0].plannedQuantity')]").isNotEmpty());

        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("NORMAL",
                                "{\"orderItemId\":999999991,\"plannedQuantity\":2,\"sourceType\":\"ORDER\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='items[0].orderItemId')]").isNotEmpty());
        assertThat(countTasks()).isZero();
    }

    @Test
    void rejectsRemakePayload() throws Exception {
        // REMAKE 不是合法任务类型：不存在可提交值，服务层无法进入成功分支
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("REMAKE",
                                "{\"orderItemId\":%d,\"plannedQuantity\":2,\"sourceType\":\"ORDER\"}"
                                        .formatted(orderItemA))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='taskType')]").isNotEmpty());
        assertThat(countTasks()).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'remake_sources'
                """, String.class)).isEmpty();
    }

    @Test
    void doesNotPersistPartialTaskOnItemFailure() throws Exception {
        // 第二条明细使用草稿订单：整批回滚，第一条也不得留下任务头、明细或计划占用
        var draft = mockMvc.perform(post("/api/orders")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"orderDate\":\"2026-09-24\",\"items\":"
                                + "[{\"productId\":" + productA + ",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        var draftOrderId = objectMapper.readTree(draft.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        var draftItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ?", Long.class, draftOrderId);
        var plannedBefore = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(making_planned), 0) FROM order_item_fulfillment_balances
                WHERE order_item_id = ?
                """, Integer.class, orderItemA);

        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("NORMAL", """
                                {"orderItemId":%d,"plannedQuantity":3,"sourceType":"ORDER"},
                                {"orderItemId":%d,"plannedQuantity":3,"sourceType":"ORDER"}
                                """.formatted(orderItemA, draftItemId))))
                .andExpect(status().isBadRequest());
        assertThat(countTasks()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM production_task_items", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(making_planned), 0) FROM order_item_fulfillment_balances
                WHERE order_item_id = ?
                """, Integer.class, orderItemA)).isEqualTo(plannedBefore);
    }

    @Test
    void rejectsNormalPlanBeyondDailyMaxCapacity() throws Exception {
        // 商品乙日最大产能 = 4 模 × 5 批 = 20；计划 21 件超过硬约束
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("NORMAL",
                                "{\"orderItemId\":%d,\"plannedQuantity\":21,\"sourceType\":\"ORDER\"}"
                                        .formatted(orderItemB))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='items[0].plannedQuantity')]").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM production_task_items WHERE product_id = ?
                """, Integer.class, productB)).isZero();

        // 产能是「产品 + 日期 + 工序」的硬约束，跨任务头合并计算：先排 12 件，再排 9 件即超出
        createTask(createBody("NORMAL",
                "{\"orderItemId\":%d,\"plannedQuantity\":6,\"sourceType\":\"ORDER\"}".formatted(orderItemB)));
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("NORMAL",
                                "{\"orderItemId\":%d,\"plannedQuantity\":6,\"sourceType\":\"ORDER\"}"
                                        .formatted(orderItemB))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("NORMAL",
                                "{\"orderItemId\":%d,\"plannedQuantity\":9,\"sourceType\":\"ORDER\"}"
                                        .formatted(orderItemB))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));
    }

    @Test
    void rejectsResignedEmployee() throws Exception {
        var body = """
                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"NORMAL",
                 "items":[{"orderItemId":%d,"plannedQuantity":2,"sourceType":"ORDER"}]}
                """.formatted(TASK_DATE, resignedId, makingWorkTypeId, orderItemA);
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_ELIGIBLE"));
        assertThat(countTasks()).isZero();
    }

    @Test
    void rejectsEmployeeWithoutWorkType() throws Exception {
        // 包装员工不具备制作工种：资格校验必须按「该工序工种」判定，而不是只看在职
        var body = """
                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"NORMAL",
                 "items":[{"orderItemId":%d,"plannedQuantity":2,"sourceType":"ORDER"}]}
                """.formatted(TASK_DATE, packerId, makingWorkTypeId, orderItemA);
        mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_NOT_ELIGIBLE"));
        assertThat(countTasks()).isZero();
    }

    @Test
    void filtersTasksByDateEmployeeAndProduct() throws Exception {
        createTask(createBody("NORMAL",
                "{\"orderItemId\":%d,\"plannedQuantity\":3,\"sourceType\":\"ORDER\"}".formatted(orderItemA)));
        mockMvc.perform(get("/api/production-tasks")
                        .cookie(sessionCookie)
                        .param("dateFrom", TASK_DATE)
                        .param("dateTo", TASK_DATE)
                        .param("employeeId", String.valueOf(makerId))
                        .param("productId", String.valueOf(productA))
                        .param("taskType", "NORMAL")
                        .param("status", "SCHEDULED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data[0].items[0].productId").value(productA));

        mockMvc.perform(get("/api/production-tasks")
                        .cookie(sessionCookie)
                        .param("productId", String.valueOf(productB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
