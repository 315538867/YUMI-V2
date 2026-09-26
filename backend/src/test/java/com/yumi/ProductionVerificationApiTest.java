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
 * 阶段五 5.7/5.8 批量核验与合格分流：逐明细一次核验、整批原子提交、
 * 制作→捏毛装袋、捏毛装袋按冻结缝边数量拆到缝边剪袋与可发货，工序数量不得相加。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionVerificationApiTest {

    private static final String USERNAME = "prod-verify-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-06";
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
        customerId = fixture.insertCustomer("TPV001", "TST-核验客户");
        var seamTypeId = fixture.insertSeamType("TST-核验缝边种类", 5);
        productId = fixture.insertProduct("TPV101", "TST-核验商品", 5, 3, seamTypeId, "TST-核验缝边种类", 10, 5);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        packingWorkTypeId = fixture.workTypeId("PACKING_BAG");
        makerId = fixture.insertEmployee("TPV201", "TST-核验制作员", "ACTIVE", "MAKING");
        packerId = fixture.insertEmployee("TPV202", "TST-核验包装员", "ACTIVE", "PACKING_BAG");
        orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 4}})[0];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPV001')";
        for (var table : new String[]{"production_reminders", "production_quantity_returns", "scrap_records",
                "rework_sources", "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TPV201', 'TPV202'))");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPV001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TPV201', 'TPV202'))");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no IN ('TPV201', 'TPV202')");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPV101'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-核验缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPV001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private long createMakingTask(int planned) throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, planned));
        return task.path("items").get(0).path("id").asLong();
    }

    private long createPackingTask(int planned) throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, packerId, packingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, planned));
        return task.path("items").get(0).path("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions verify(long taskId, String itemsJson)
            throws Exception {
        return mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                .cookie(sessionCookie)
                .header("Idempotency-Key", ProductionFixture.key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[" + itemsJson + "]}"));
    }

    private String itemInput(long itemId, int qualified, int rework, int scrap) {
        return "{\"taskItemId\":%d,\"qualifiedQuantity\":%d,\"reworkQuantity\":%d,\"scrapQuantity\":%d}"
                .formatted(itemId, qualified, rework, scrap);
    }

    /** 本测试客户名下所有订单在该节点上的合格流入合计（跨订单明细）。 */
    private int entryQuantity(String node, String direction) {
        return fixture.count("""
                SELECT COALESCE(SUM(e.quantity), 0) FROM fulfillment_entries e
                JOIN orders o ON o.id = e.order_id
                WHERE o.customer_id = (SELECT id FROM customers WHERE customer_no = 'TPV001')
                  AND e.node = ? AND e.direction = ? AND e.entry_type = 'PRODUCTION_QUALIFIED'
                """, node, direction);
    }

    @Test
    void makingQualifiedFlowsOnlyToPacking() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();

        verify(taskId, itemInput(itemId, 6, 0, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].qualifiedQuantity").value(6))
                .andExpect(jsonPath("$.data.items[0].incompleteQuantity").value(4))
                .andExpect(jsonPath("$.data.items[0].flows[0].node").value("PACKING_BAG"))
                .andExpect(jsonPath("$.data.derivedStatus").value("VERIFIED"));

        // 制作合格只进捏毛装袋，不直接可发货
        assertThat(entryQuantity("PACKING_BAG", "IN")).isEqualTo(6);
        assertThat(entryQuantity("SHIPPABLE", "IN")).isZero();
        assertThat(fixture.count(
                "SELECT packing_inflow FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                orderItemId)).isEqualTo(6);
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM production_verifications WHERE task_item_id = ?
                """, itemId)).isEqualTo(1);
        // 明细转为已核验，且计划占用已释放
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM production_task_items WHERE id = ?", String.class, itemId))
                .isEqualTo("VERIFIED");
    }

    @Test
    void packingSplitsByFrozenSeamQuantity() throws Exception {
        var makingTask = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        verify(makingTask.path("id").asLong(),
                itemInput(makingTask.path("items").get(0).path("id").asLong(), 10, 0, 0))
                .andExpect(status().isOk());

        var packingTask = fixture.createTask(sessionCookie, TASK_DATE, packerId, packingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        var packingTaskId = packingTask.path("id").asLong();
        var packingItemId = packingTask.path("items").get(0).path("id").asLong();
        // 上游实际合格 10 → 捏毛装袋可执行 10
        assertThat(packingTask.path("items").get(0).path("executableQuantity").asInt()).isEqualTo(10);

        verify(packingTaskId, itemInput(packingItemId, 10, 0, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].flows[0].node").value("SEAM_CUTTING"))
                .andExpect(jsonPath("$.data.items[0].flows[0].quantity").value(4))
                .andExpect(jsonPath("$.data.items[0].flows[1].node").value("SHIPPABLE"))
                .andExpect(jsonPath("$.data.items[0].flows[1].quantity").value(6));

        // 冻结缝边数量 4 进缝边剪袋，其余 6 进可发货；订单需求仍是订购数量 10，不把工序数量相加
        assertThat(entryQuantity("SEAM_CUTTING", "IN")).isEqualTo(4);
        assertThat(entryQuantity("SHIPPABLE", "IN")).isEqualTo(6);
        assertThat(fixture.count(
                "SELECT required_quantity FROM order_item_fulfillment_balances WHERE order_item_id = ?",
                orderItemId)).isEqualTo(10);
        // 生产核验只写履约事实：不产生库存流水、不产生库存批次（发货前不得动库存）
        assertThat(fixture.count("SELECT COUNT(*) FROM inventory_movements")).isZero();
        assertThat(fixture.count("SELECT COUNT(*) FROM inventory_batches")).isZero();
    }

    @Test
    void rejectsEquationViolationAndOverExecutable() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();

        // 空数组与负数按字段错误拒绝
        verify(taskId, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));
        verify(taskId, "{\"taskItemId\":%d,\"qualifiedQuantity\":-1}".formatted(itemId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"));

        // 本次完成不得超过计划数量（合格 11 > 计划 10）
        verify(taskId, itemInput(itemId, 11, 0, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_EQUATION_INVALID"));
        assertThat(fixture.count("SELECT COUNT(*) FROM production_verifications")).isZero();

        // 超过当前可执行量：捏毛装袋尚无上游合格流入 → 可执行 0，任何完成数量都被拒
        var packingTask = fixture.createTask(sessionCookie, TASK_DATE, packerId, packingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 5));
        verify(packingTask.path("id").asLong(),
                itemInput(packingTask.path("items").get(0).path("id").asLong(), 1, 0, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_NOT_EXECUTABLE"));
        assertThat(fixture.count("SELECT COUNT(*) FROM production_verifications")).isZero();
    }

    @Test
    void rejectsSecondVerification() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 4));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();

        verify(taskId, itemInput(itemId, 4, 0, 0)).andExpect(status().isOk());
        verify(taskId, itemInput(itemId, 1, 0, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_ALREADY_VERIFIED"));
        assertThat(fixture.count("SELECT COUNT(*) FROM production_verifications")).isEqualTo(1);
        assertThat(entryQuantity("PACKING_BAG", "IN")).isEqualTo(4);
    }

    @Test
    void verifiesMultipleItemsInOneRequest() throws Exception {
        var secondItem = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 8, 0}})[0];
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 6) + "," + fixture.orderSourceItem(secondItem, 5));
        var taskId = task.path("id").asLong();
        var firstItemId = task.path("items").get(0).path("id").asLong();
        var secondItemId = task.path("items").get(1).path("id").asLong();

        verify(taskId, itemInput(firstItemId, 6, 0, 0) + "," + itemInput(secondItemId, 5, 0, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.derivedStatus").value("VERIFIED"));
        assertThat(fixture.count("SELECT COUNT(*) FROM production_verifications")).isEqualTo(2);
        assertThat(entryQuantity("PACKING_BAG", "IN")).isEqualTo(11);
    }

    @Test
    void rollsBackAllItemsAndDerivedFactsWhenOneItemFails() throws Exception {
        var secondItem = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 8, 0}})[0];
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 6) + "," + fixture.orderSourceItem(secondItem, 5));
        var taskId = task.path("id").asLong();
        var firstItemId = task.path("items").get(0).path("id").asLong();
        var secondItemId = task.path("items").get(1).path("id").asLong();

        // 第二条超过计划数量（本次完成 9 > 计划 5）：整批回滚，第一条不得留下核验或流转事实
        verify(taskId, itemInput(firstItemId, 6, 0, 0) + "," + itemInput(secondItemId, 9, 0, 0))
                .andExpect(status().isBadRequest());
        assertThat(fixture.count("SELECT COUNT(*) FROM production_verifications")).isZero();
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM fulfillment_entries WHERE entry_type = 'PRODUCTION_QUALIFIED'
                """)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT status FROM production_task_items WHERE task_id = ? ORDER BY item_no
                """, String.class, taskId)).containsExactly("PENDING", "PENDING");
    }

    @Test
    void createsIncompleteReminderAndReturnsQuantityToSchedulable() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 10));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();

        verify(taskId, itemInput(itemId, 4, 0, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].incompleteQuantity").value(6))
                .andExpect(jsonPath("$.data.items[0].incompleteReminderId").isNotEmpty());

        var reminder = jdbcTemplate.queryForMap("""
                SELECT reminder_type, quantity, status FROM production_reminders WHERE task_item_id = ?
                """, itemId);
        assertThat(reminder.get("reminder_type")).isEqualTo("INCOMPLETE");
        assertThat(((Number) reminder.get("quantity")).intValue()).isEqualTo(6);
        assertThat(reminder.get("status")).isEqualTo("OPEN");

        // 未完成部分回到普通待安排：可重新排产 6 件
        var rePlanned = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 6));
        assertThat(rePlanned.path("items").get(0).path("plannedQuantity").asInt()).isEqualTo(6);
    }
}
