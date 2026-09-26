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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.16 并发门禁：竞争性创建、来源消费与核验必须在锁内重算余额，
 * 恰好一个事务成功、另一个返回业务冲突，失败事务不留下任何部分事实。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionConcurrencyTest {

    private static final String USERNAME = "prod-concurrency-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String TASK_DATE = "2026-10-08";
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
    private long makingWorkTypeId;
    private long productId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        fixture = new ProductionFixture(mockMvc, jdbcTemplate, passwordEncoder, objectMapper);
        fixture.insertAdmin(USERNAME, PASSWORD);
        sessionCookie = fixture.login(USERNAME, PASSWORD);
        customerId = fixture.insertCustomer("TPC001", "TST-并发客户");
        var seamTypeId = fixture.insertSeamType("TST-并发缝边种类", 5);
        // 日最大产能 = 10 × 1 = 10，便于构造并发越界
        productId = fixture.insertProduct("TPC101", "TST-并发商品", 5, 3, seamTypeId, "TST-并发缝边种类", 10, 1);
        makingWorkTypeId = fixture.workTypeId("MAKING");
        makerId = fixture.insertEmployee("TPC201", "TST-并发制作员", "ACTIVE", "MAKING");
        orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}})[0];
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPC001')";
        jdbcTemplate.update("DELETE FROM rework_sources WHERE previous_source_id IS NOT NULL"
                + " AND order_id IN (" + testOrders + ")");
        for (var table : new String[]{"production_reminders", "production_quantity_returns", "scrap_records",
                "rework_sources", "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPC201')");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPC001')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPC201')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPC201'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TPC101'");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-并发缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPC001'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    /** 并发执行同一写命令，返回各线程的 HTTP 状态码。 */
    private List<Integer> runConcurrently(List<Callable<Integer>> calls) throws Exception {
        var barrier = new CyclicBarrier(calls.size());
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (var call : calls) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            var statuses = new ArrayList<Integer>();
            for (var future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Integer> createNormalTask(int quantity) {
        return () -> mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"NORMAL",
                                 "items":[{"orderItemId":%d,"plannedQuantity":%d,"sourceType":"ORDER"}]}
                                """.formatted(TASK_DATE, makerId, makingWorkTypeId, orderItemId, quantity)))
                .andReturn().getResponse().getStatus();
    }

    private Callable<Integer> createReworkTask(long sourceId, int quantity) {
        return () -> mockMvc.perform(post("/api/rework-sources/" + sourceId + "/tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"items":[{"plannedQuantity":%d}]}
                                """.formatted(TASK_DATE, makerId, quantity)))
                .andReturn().getResponse().getStatus();
    }

    private Callable<Integer> verifyItem(long taskId, long itemId, int qualified) {
        return () -> mockMvc.perform(post("/api/production-tasks/" + taskId + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":%d,"reworkQuantity":0,
                                 "scrapQuantity":0}]}
                                """.formatted(itemId, qualified)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void twoConcurrentNormalCreatesCannotExceedCapacity() throws Exception {
        // 日产能 10，两笔各 6 件：恰好一笔成功
        var statuses = runConcurrently(List.of(createNormalTask(6), createNormalTask(6)));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        var planned = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(planned_quantity), 0) FROM production_task_items
                WHERE product_id = ? AND status <> 'CANCELLED'
                """, Integer.class, productId);
        assertThat(planned).isEqualTo(6);
        assertThat(fixture.count("SELECT COUNT(*) FROM production_tasks")).isEqualTo(1);
    }

    @Test
    void twoConcurrentReworkArrangementsCannotExceedSourceBalance() throws Exception {
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
        var sourceResponse = mockMvc.perform(post("/api/rework-sources")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originVerificationId\":%d,\"quantity\":3,\"reason\":\"并发\"}"
                                .formatted(verificationId)))
                .andExpect(status().isCreated())
                .andReturn();
        var sourceId = objectMapper.readTree(sourceResponse.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 来源余额 3，两笔各 2 件：恰好一笔成功
        var statuses = runConcurrently(List.of(createReworkTask(sourceId, 2), createReworkTask(sourceId, 2)));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT arranged_quantity FROM rework_sources WHERE id = ?", Integer.class, sourceId))
                .isEqualTo(2);
        assertThat(fixture.count("""
                SELECT COUNT(*) FROM production_task_items WHERE source_type = 'REWORK_SOURCE'
                """)).isEqualTo(1);
    }

    private Callable<Integer> createReturnTask(long returnId, int quantity) {
        return () -> mockMvc.perform(post("/api/production-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,"workTypeId":%d,"taskType":"NORMAL",
                                 "items":[{"orderItemId":%d,"plannedQuantity":%d,
                                 "sourceType":"QUANTITY_RETURN","sourceId":%d}]}
                                """.formatted(TASK_DATE, makerId, makingWorkTypeId, orderItemId, quantity,
                                returnId)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void twoConcurrentReturnAllocationsCannotOversell() throws Exception {
        // 报废 2 件 → 同工序回转余额 2（产能只占 2，避免产能先于余额触发拒绝）
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 2));
        mockMvc.perform(post("/api/production-tasks/" + task.path("id").asLong() + "/verify")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"taskItemId":%d,"qualifiedQuantity":0,"reworkQuantity":0,
                                 "scrapQuantity":2}]}
                                """.formatted(task.path("items").get(0).path("id").asLong())))
                .andExpect(status().isOk());
        var returnId = jdbcTemplate.queryForObject(
                "SELECT id FROM production_quantity_returns", Long.class);

        var statuses = runConcurrently(List.of(createReturnTask(returnId, 2), createReturnTask(returnId, 2)));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT allocated_quantity FROM production_quantity_returns WHERE id = ?", Integer.class,
                returnId)).isEqualTo(2);
    }

    @Test
    void twoConcurrentVerificationsKeepSingleFact() throws Exception {
        var task = fixture.createTask(sessionCookie, TASK_DATE, makerId, makingWorkTypeId, "NORMAL",
                fixture.orderSourceItem(orderItemId, 4));
        var taskId = task.path("id").asLong();
        var itemId = task.path("items").get(0).path("id").asLong();

        var statuses = runConcurrently(List.of(verifyItem(taskId, itemId, 4), verifyItem(taskId, itemId, 4)));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(fixture.count("SELECT COUNT(*) FROM production_verifications")).isEqualTo(1);
        assertThat(fixture.count("""
                SELECT COALESCE(SUM(quantity), 0) FROM fulfillment_entries
                WHERE entry_type = 'PRODUCTION_QUALIFIED' AND node = 'PACKING_BAG'
                """)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM production_task_items WHERE id = ?", String.class, itemId))
                .isEqualTo("VERIFIED");
    }
}
