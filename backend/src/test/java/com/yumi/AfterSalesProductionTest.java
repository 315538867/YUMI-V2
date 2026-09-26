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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 8.4/8.5（阶段五重构后）：售后返工来源与售后补发生产来源——从售后来源创建生产任务。
 *
 * <p>阶段五只保留售后生产的**来源边界**：售后任务必须使用售后专用来源
 * （`after_sales_production_sources`，`source_type = AFTER_SALES_SOURCE`），
 * 不登记订单侧计划占用、不占用产品日产能（`dailyMaxCapacity`）、不产生订单需求或履约事实、
 * 不自动进入通用库存；售后核验与合格流向属于阶段八业务，阶段五不写售后业务事实。
 * 口径见 `docs/architecture/production-module-design.md` §2.2/§9/§11 与
 * `docs/architecture/after-sales-module-design.md` §4.3。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AfterSalesProductionTest {

    private static final String USERNAME = "after-sales-production-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-09-26";

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
    private long caseId;
    private long itemId;
    private long makerId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, mold_quantity, daily_batch_limit, version, created_at, updated_at)
                VALUES ('TAP001', 'TST-售后生产商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 10, 5, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TAP001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TAP001', 'TST-售后生产客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TAP001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TAP0001', ?, 'TST-售后生产客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TAP0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TAP001', 'TST-售后生产商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        // 阶段五：生产域只读订单确认时的生产参数快照，售后生产任务也必须冻结标准分钟与能力快照
        jdbcTemplate.update("""
                INSERT INTO order_item_snapshots (order_id, order_item_id, line_no, product_id, product_no,
                    product_name, star_std_minutes, packaging_std_minutes, quantity, seam_quantity,
                    making_effective_hour_rate, workday_hours, mold_quantity, daily_batch_limit, flow,
                    created_at, updated_at)
                VALUES (?, ?, 1, ?, 'TAP001', 'TST-售后生产商品', 5, 3, 10, 0, 0.600000, 7.5000, 10, 5,
                    'MAKING,PACKING_BAG,SEAM_CUTTING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId, productId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    shippable_quantity, shipped_quantity, version, created_at, updated_at)
                VALUES (?, ?, 10, 6, 4, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        jdbcTemplate.update("""
                INSERT INTO shipments (shipment_no, order_id, status, shipment_date, freight, current_freight,
                    created_at, updated_at)
                VALUES ('SH970001', ?, 'CONFIRMED', '2026-09-25', 0.0000, 0.0000, UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6))
                """, orderId);
        long shipmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipments WHERE shipment_no = 'SH970001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO shipment_items (shipment_id, order_item_id, line_no, quantity, product_no,
                    product_name, created_at, updated_at)
                VALUES (?, ?, 1, 4, 'TAP001', 'TST-售后生产商品', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, shipmentId, orderItemId);
        long shipmentItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM shipment_items WHERE shipment_id = ?", Long.class, shipmentId);
        makerId = insertEmployee("TPA001", "TST-售后生产制作工", "MAKING");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
        // 售后单：受理 4、退回核验 3 = 返工 2 + 报废 1、补发需求 4
        var created = mockMvc.perform(post("/api/orders/" + orderId + "/after-sales")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseType":"REWORK_AND_REPLACEMENT","problem":"客户反馈破损",
                                 "items":[{"shipmentItemId":%d,"acceptedQuantity":4,"returnedQuantity":3,
                                 "replacementRequiredQuantity":4}]}
                                """.formatted(shipmentItemId)))
                .andExpect(status().isCreated())
                .andReturn();
        caseId = dataId(created);
        itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM after_sales_items WHERE case_id = ?", Long.class, caseId);
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE order_no = 'TAP0001'";
        var cases = "SELECT id FROM after_sales_cases WHERE order_id IN (" + testOrders + ")";
        var items = "SELECT id FROM after_sales_items WHERE case_id IN (" + cases + ")";
        var shipments = "SELECT id FROM shipments WHERE order_id IN (" + testOrders + ")";
        jdbcTemplate.update("DELETE FROM after_sales_production_sources WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_corrections WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_fulfillment_entries WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_shipment_links WHERE after_sales_item_id IN (" + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_return_verifications WHERE after_sales_item_id IN ("
                + items + ")");
        jdbcTemplate.update("DELETE FROM after_sales_items WHERE case_id IN (" + cases + ")");
        jdbcTemplate.update("DELETE FROM after_sales_cases WHERE order_id IN (" + testOrders + ")");
        // 售后生产任务（新模型）：核验事实 → 明细 → 任务头
        List<Long> taskIds = jdbcTemplate.queryForList("SELECT DISTINCT task_id FROM production_task_items "
                + "WHERE order_id IN (" + testOrders + ")", Long.class);
        if (!taskIds.isEmpty()) {
            var idList = String.join(",", taskIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM production_verifications WHERE task_item_id IN "
                    + "(SELECT id FROM production_task_items WHERE task_id IN (" + idList + "))");
            jdbcTemplate.update("DELETE FROM production_task_items WHERE task_id IN (" + idList + ")");
            jdbcTemplate.update("DELETE FROM production_tasks WHERE id IN (" + idList + ")");
        }
        jdbcTemplate.update("DELETE FROM shipment_source_links WHERE shipment_item_id IN "
                + "(SELECT id FROM shipment_items WHERE shipment_id IN (" + shipments + "))");
        jdbcTemplate.update("DELETE FROM shipment_items WHERE shipment_id IN (" + shipments + ")");
        jdbcTemplate.update("DELETE FROM shipments WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_item_snapshots WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TAP0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TAP001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TAP001'");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPA001')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPA001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void reworkTaskConsumesAfterSalesSourceWithoutTouchingOrderFulfillment() throws Exception {
        // 未核验退回前没有返工额度
        createTask("REWORK", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));

        verifyReturn(3, 2, 1).andExpect(status().isOk());
        // 返工额度 = 退回核验的返工数量 2
        createTask("REWORK", 3).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='quantity')]").isNotEmpty());

        int fulfillmentBefore = count("fulfillment_entries");
        String balancesBefore = balanceSnapshot();
        var created = createTask("REWORK", 2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.taskType").value("REWORK"))
                .andExpect(jsonPath("$.data.items[0].sourceType").value("AFTER_SALES_SOURCE"))
                .andExpect(jsonPath("$.data.items[0].plannedQuantity").value(2))
                .andExpect(jsonPath("$.data.items[0].node").value("MAKING"))
                .andReturn();
        long taskId = dataId(created);
        // 售后生产任务明细存在：来源类型为售后来源，数量、工序与归属与来源一致
        assertThat(count("production_task_items")).isEqualTo(1);
        assertThat(taskItem(taskId, "source_type")).isEqualTo("AFTER_SALES_SOURCE");
        assertThat(taskItem(taskId, "planned_quantity")).isEqualTo("2");
        assertThat(taskItem(taskId, "node")).isEqualTo("MAKING");
        assertThat(taskItem(taskId, "order_item_id")).isEqualTo(String.valueOf(orderItemId));
        // 来源行记录额度与占用
        assertThat(sourceArranged("REWORK")).isEqualTo(2);
        // 售后任务不占用订单工序需求、不改变订单履约投影、不产生订单履约事实
        assertThat(balanceSnapshot()).as("售后任务不得改变订单履约投影").isEqualTo(balancesBefore);
        assertThat(count("fulfillment_entries")).as("不产生订单履约事实").isEqualTo(fulfillmentBefore);
        assertThat(orderCapacityUsage()).as("售后任务不占用产品日产能").isZero();
        // 不自动进入通用库存
        assertThat(count("inventory_batches")).as("不自动新建库存批次").isZero();
    }

    @Test
    void replacementTaskCoversShortageWithoutEnteringInventory() throws Exception {
        verifyReturn(3, 2, 1).andExpect(status().isOk());
        // 报废 1：不入库、不恢复库存、不计已补发
        assertThat(count("inventory_batches")).isZero();
        assertThat(count("inventory_movements")).isZero();
        assertThat(availableQuantity()).isZero();
        assertThat(shippedQuantity()).isZero();

        // 补发缺口 = 补发需求 4 − 已补发 0 − 可补发 0 = 4；超量被拒
        createTask("REPLACEMENT", 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));
        var created = createTask("REPLACEMENT", 4).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.taskType").value("NORMAL"))
                .andExpect(jsonPath("$.data.items[0].sourceType").value("AFTER_SALES_SOURCE"))
                .andReturn();
        long taskId = dataId(created);
        // 来源行记录额度与占用，读模型可见
        mockMvc.perform(get("/api/after-sales/" + caseId + "/production-sources").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.purpose=='REPLACEMENT')].afterSalesItemId").value((int) itemId))
                .andExpect(jsonPath("$.data[?(@.purpose=='REPLACEMENT')].node").value("MAKING"))
                .andExpect(jsonPath("$.data[?(@.purpose=='REPLACEMENT')].totalQuantity").value(4))
                .andExpect(jsonPath("$.data[?(@.purpose=='REPLACEMENT')].arrangedQuantity").value(4))
                .andExpect(jsonPath("$.data[?(@.purpose=='REPLACEMENT')].availableQuantity").value(0));
        assertThat(taskItem(taskId, "source_type")).isEqualTo("AFTER_SALES_SOURCE");
        assertThat(taskItem(taskId, "planned_quantity")).isEqualTo("4");
        assertThat(count("inventory_batches")).as("售后生产合格前不产生库存批次").isZero();
        assertThat(availableQuantity()).as("核验前不写售后补发台账").isZero();
        assertThat(count("fulfillment_entries")).as("不产生订单履约事实").isZero();
        assertThat(orderCapacityUsage()).as("补发任务不占用产品日产能").isZero();

        // 可补发缺口已被来源排满：不再允许为同一需求排产
        createTask("REPLACEMENT", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_INSUFFICIENT"));
    }

    @Test
    void rejectsTaskForItemOfAnotherCaseAndBadRequests() throws Exception {
        verifyReturn(3, 2, 1).andExpect(status().isOk());
        mockMvc.perform(post("/api/after-sales/999999/production-sources/tasks").cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"afterSalesItemId":%d,"purpose":"REWORK","node":"MAKING","planDate":"%s",
                                 "employeeId":%d,"quantity":1}
                                """.formatted(itemId, TASK_DATE, makerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
        // 用途只允许 REWORK / REPLACEMENT：非法用途 400 且不写任何行
        mockMvc.perform(post("/api/after-sales/" + caseId + "/production-sources/tasks").cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"afterSalesItemId":%d,"purpose":"SCRAP","node":"MAKING","planDate":"%s",
                                 "employeeId":%d,"quantity":1}
                                """.formatted(itemId, TASK_DATE, makerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='purpose')]").isNotEmpty());
        assertThat(count("after_sales_production_sources")).isZero();
        assertThat(count("production_task_items")).isZero();
        assertThat(count("production_tasks")).isZero();
    }

    // ---------- 工具 ----------

    private org.springframework.test.web.servlet.ResultActions createTask(String purpose, int quantity)
            throws Exception {
        return mockMvc.perform(post("/api/after-sales/" + caseId + "/production-sources/tasks")
                .cookie(sessionCookie).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"afterSalesItemId":%d,"purpose":"%s","node":"MAKING","planDate":"%s",
                         "employeeId":%d,"quantity":%d}
                        """.formatted(itemId, purpose, TASK_DATE, makerId, quantity)));
    }

    private org.springframework.test.web.servlet.ResultActions verifyReturn(int returned, int rework, int scrap)
            throws Exception {
        return mockMvc.perform(post("/api/after-sales/" + caseId + "/verify-return").cookie(sessionCookie)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"afterSalesItemId":%d,"returnedQuantity":%d,"reworkQuantity":%d,"scrapQuantity":%d,
                         "reason":"客户退回"}
                        """.formatted(itemId, returned, rework, scrap)));
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

    /** 指定任务明细的单个字段（文本形式，避免列类型差异）。 */
    @Test
    void afterSalesProductionVerificationEntersAfterSalesLedger() throws Exception {
        verifyReturn(3, 2, 1).andExpect(status().isOk());
        var created = createTask("REPLACEMENT", 4).andExpect(status().isCreated()).andReturn();
        long taskId = dataId(created);
        long taskItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM production_task_items WHERE task_id = ?", Long.class, taskId);
        assertThat(availableQuantity()).as("核验前不写售后可补发台账").isZero();

        // 核验合格 4：进入售后可补发台账，不写订单工序流入、不产生库存、不直接增加已补发
        mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":4,"reworkQuantity":0,
                                 "scrapQuantity":0}]}
                                """.formatted(taskItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].flows[0].node").value("AFTER_SALES_AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].flows[0].quantity").value(4));

        assertThat(availableQuantity()).as("合格进入售后可补发台账").isEqualTo(4);
        assertThat(shippedQuantity()).as("补发仍需单独确认，不直接增加已补发").isZero();
        assertThat(count("fulfillment_entries")).as("不写订单履约事实").isZero();
        assertThat(count("inventory_movements")).as("不产生库存流水").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT CAST(status AS CHAR) FROM production_task_items WHERE id = ?
                """, String.class, taskItemId)).isEqualTo("VERIFIED");
    }

    private String taskItem(long taskId, String column) {
        return jdbcTemplate.queryForObject("SELECT CAST(" + column + " AS CHAR) FROM production_task_items "
                + "WHERE task_id = ?", String.class, taskId);
    }

    /** 该售后明细的售后可补发余额（售后台账净额；售后生产核验合格后由本阶段写入）。 */
    private int availableQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END), 0)
                FROM after_sales_fulfillment_entries WHERE after_sales_item_id = ?
                """, Integer.class, itemId);
        return value == null ? 0 : value;
    }

    private int shippedQuantity() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM after_sales_fulfillment_entries
                WHERE after_sales_item_id = ? AND entry_type = 'REPLACEMENT_CONSUME' AND direction = 'OUT'
                """, Integer.class, itemId);
        return value == null ? 0 : value;
    }

    private int sourceArranged(String purpose) {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(arranged_quantity), 0)
                FROM after_sales_production_sources WHERE after_sales_item_id = ? AND purpose = ?
                """, Integer.class, itemId, purpose);
        return value == null ? 0 : value;
    }

    /**
     * 订单侧正常产能占用：只统计正常来源（`ORDER`/`QUANTITY_RETURN`）。
     * 售后任务必须不占用产品 `dailyMaxCapacity`，故该值必须为 0。
     */
    private int orderCapacityUsage() {
        var value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(i.planned_quantity), 0) FROM production_task_items i
                JOIN production_tasks t ON t.id = i.task_id
                WHERE i.product_id = (SELECT id FROM products WHERE product_no = 'TAP001')
                  AND t.task_date = '2026-09-26' AND i.node = 'MAKING' AND i.status <> 'CANCELLED'
                  AND i.source_type IN ('ORDER', 'QUANTITY_RETURN')
                """, Integer.class);
        return value == null ? 0 : value;
    }

    /** 订单履约投影快照：售后生产不得改变订单侧排产与履约数量。 */
    private String balanceSnapshot() {
        return jdbcTemplate.queryForObject("""
                SELECT CONCAT(required_quantity, '/', shippable_quantity, '/', shipped_quantity, '/',
                              making_planned, '/', packing_planned, '/', seam_planned, '/', verified_processed)
                FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, String.class, orderItemId);
    }

    private long dataId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private int count(String table) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private static String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
