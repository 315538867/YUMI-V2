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

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.13 取消与核验的并发门禁：同一明细上「取消」与「核验」竞争时只允许一个状态迁移，
 * 失败方不留任何部分事实，最终状态只能是 CANCELLED 或 VERIFIED 之一。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionTaskItemCancelConcurrencyTest {

    private static final String USERNAME = "prod-cancel-race-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-14";
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
        var customerId = fixture.insertCustomer("TPK001", "TST-取消竞争客户");
        var seamTypeId = fixture.insertSeamType("TST-取消竞争缝边种类", 5);
        var productId = fixture.insertProduct("TPK101", "TST-取消竞争商品", 5, 3, seamTypeId,
                "TST-取消竞争缝边种类", 10, 5);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        makerId = fixture.insertEmployee("TPK201", "TST-取消竞争制作员", "ACTIVE", "MAKING");
        orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}})[0];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPK001')";
        jdbcTemplate.update("DELETE FROM rework_sources WHERE previous_source_id IS NOT NULL"
                + " AND order_id IN (" + testOrders + ")");
        for (var table : new String[]{"production_reminders", "production_quantity_returns", "scrap_records",
                "rework_sources", "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPK201')");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPK001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPK201')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPK201'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPK101'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-取消竞争缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPK001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void concurrentCancelAndVerifyAllowsOneStateTransition() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 4));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();

        Callable<Integer> cancel = () -> mockMvc.perform(
                        post("/api/production-tasks/" + taskId + "/items/" + itemId + "/cancel")
                                .cookie(sessionCookie)
                                .header("Idempotency-Key", ProductionFixture.key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"并发取消\"}"))
                .andReturn().getResponse().getStatus();
        Callable<Integer> verify = () -> mockMvc.perform(
                        post("/api/production-tasks/" + taskId + "/verify")
                                .cookie(sessionCookie)
                                .header("Idempotency-Key", ProductionFixture.key())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"items":[{"taskItemId":%d,"qualifiedQuantity":4,
                                         "reworkQuantity":0,"scrapQuantity":0}]}
                                        """.formatted(itemId)))
                .andReturn().getResponse().getStatus();

        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            var futures = List.of(
                    pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return cancel.call();
                    }),
                    pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return verify.call();
                    }));
            statuses = List.of(futures.get(0).get(30, TimeUnit.SECONDS),
                    futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        // 恰好一个状态迁移成功，另一个被状态守卫拒绝
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        var finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM production_task_items WHERE id = ?", String.class, itemId);
        assertThat(finalStatus).isIn("CANCELLED", "VERIFIED");
        // 状态与事实一致：已核验必有一条核验，已取消必无核验与流转
        int verifications = fixture.count(
                "SELECT COUNT(*) FROM production_verifications WHERE task_item_id = ?", itemId);
        if ("VERIFIED".equals(finalStatus)) {
            assertThat(verifications).isEqualTo(1);
        } else {
            assertThat(verifications).isZero();
            assertThat(fixture.count("""
                    SELECT COUNT(*) FROM fulfillment_entries WHERE entry_type = 'PRODUCTION_QUALIFIED'
                    """)).isZero();
        }
        // 计划占用与最终状态一致：已取消释放，已核验也释放（核验完成不再占待执行资源）
        assertThat(fixture.count("""
                SELECT COALESCE(SUM(making_planned), 0) FROM order_item_fulfillment_balances
                WHERE order_item_id = ?
                """, orderItemId)).isZero();
    }
}
