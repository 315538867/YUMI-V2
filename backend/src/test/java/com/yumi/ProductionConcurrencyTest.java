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

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务 5.16（并发部分）：来源余额竞争、超额预占竞争、可执行上限竞争、同一计划唯一核验竞争。
 * 口径：`design.md` §4（事务内以稳定顺序锁定履约余额 → 计划 → 来源 → 预占）与施工文档 §8。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionConcurrencyTest {

    private static final String USERNAME = "production-concurrency-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";

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
    private long makerId;
    private long packerId;

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (username, password_hash, status, version, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, version, created_at, updated_at)
                VALUES ('TCC001', 'TST-并发商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TCC001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TCC001', 'TST-并发客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TCC001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TCC0001', ?, 'TST-并发客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 120.0000, 0.0000, 0.0000, 120.0000, 72.0000, 0.0000, 72.0000, 48.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TCC0001'", Long.class);
        // Q=12：可容纳两条各 6 件的计划，用于可执行上限竞争
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TCC001', 'TST-并发商品', 12, 0, 10.0000, 120.0000, 6.0000, 72.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO order_item_fulfillment_balances (order_id, order_item_id, required_quantity,
                    making_inflow, version, created_at, updated_at)
                VALUES (?, ?, 12, 10, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId);
        makerId = insertEmployee("TCE001", "TST-制作员工", "MAKING");
        packerId = insertEmployee("TCE002", "TST-包装员工", "PACKING_BAG");
        var login = mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        var testOrder = "SELECT id FROM orders WHERE order_no = 'TCC0001'";
        var testPlans = "SELECT id FROM production_plans WHERE order_id IN (" + testOrder + ")";
        jdbcTemplate.update("DELETE FROM production_reminders WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM overtime_preemptions WHERE overtime_plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM remake_sources WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM rework_sources WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM production_verifications WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plan_adjustments WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plans WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM fulfillment_entries WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_item_fulfillment_balances WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN (" + testOrder + ")");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TCC0001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TCC001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TCC001'");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no IN ('TCE001', 'TCE002'))");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no IN ('TCE001', 'TCE002')");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void reworkSourceBalanceRaceAllowsOnlyOnePlan() throws Exception {
        long sourceId = reworkSourceOfThree();

        Callable<Integer> createPlan = () -> mockMvc.perform(post("/api/rework-sources/" + sourceId + "/plans")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planDate":"2026-09-25","employeeId":%d,"quantity":2}
                                """.formatted(makerId)))
                .andReturn().getResponse().getStatus();

        var statuses = runConcurrently(createPlan, createPlan);
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        // 来源余额只被扣一次：总量 3 − 已安排 2 = 1
        assertThat(jdbcTemplate.queryForObject("""
                SELECT total_quantity - arranged_quantity FROM rework_sources WHERE id = ?
                """, Integer.class, sourceId)).isEqualTo(1);
        assertThat(planCount()).isEqualTo(2); // 1 条正常计划（核验用）+ 1 条返工计划
    }

    @Test
    void overtimePreemptionRaceAllowsOnlyOneTask() throws Exception {
        long futurePlanId = createNormalPlan("MAKING", makerId, LocalDate.now().plusDays(1), 6);

        Callable<Integer> createTask = () -> mockMvc.perform(post("/api/overtime-tasks")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderItemId":%d,"node":"MAKING","planDate":"%s","employeeId":%d,
                                 "lines":[{"futurePlanId":%d,"quantity":4}]}
                                """.formatted(orderItemId, LocalDate.now(), makerId, futurePlanId)))
                .andReturn().getResponse().getStatus();

        var statuses = runConcurrently(createTask, createTask);
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        // 未来计划原始数量不变；有效预占合计只有 4
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quantity FROM production_plans WHERE id = ?", Integer.class, futurePlanId)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(preempted_quantity), 0) FROM overtime_preemptions
                WHERE future_plan_id = ? AND status = 'ACTIVE'
                """, Integer.class, futurePlanId)).isEqualTo(4);
    }

    @Test
    void executableLimitRaceAllowsOnlyOneVerification() throws Exception {
        // 下游工序（捏毛装袋）有效流入只有 6，两条各 6 件的计划中只有一条能核验
        // （首道制作的有效流入是订单需求 Q，不用于本用例）
        jdbcTemplate.update("UPDATE order_item_fulfillment_balances SET packing_inflow = 6 "
                + "WHERE order_item_id = ?", orderItemId);
        long first = createNormalPlan("PACKING_BAG", packerId, LocalDate.now(), 6);
        long second = createNormalPlan("PACKING_BAG", packerId, LocalDate.now().plusDays(1), 6);

        Callable<Integer> verifyFirst = () -> verifyStatus(first, 6);
        Callable<Integer> verifySecond = () -> verifyStatus(second, 6);
        var statuses = runConcurrently(verifyFirst, verifySecond);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(completed_quantity), 0) FROM production_verifications
                WHERE order_item_id = ? AND node = 'PACKING_BAG'
                """, Integer.class, orderItemId)).as("核验处理不超过可执行数量").isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT verified_processed FROM order_item_fulfillment_balances WHERE order_item_id = ?
                """, Integer.class, orderItemId)).isEqualTo(6);
    }

    @Test
    void duplicateVerificationRaceKeepsSingleVerification() throws Exception {
        long planId = createNormalPlan("MAKING", makerId, LocalDate.now(), 5);

        Callable<Integer> verify = () -> verifyStatus(planId, 5);
        var statuses = runConcurrently(verify, verify);

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM production_verifications WHERE plan_id = ?", Integer.class, planId))
                .as("每计划最多一次有效核验").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM production_plans WHERE id = ?", String.class, planId)).isEqualTo("VERIFIED");
    }

    // ---------- 工具 ----------

    /** 造一条「返工 3 件」的核验并创建目标为制作的返工来源（总量 3）。 */
    private long reworkSourceOfThree() throws Exception {
        long planId = createNormalPlan("MAKING", makerId, LocalDate.now(), 10);
        var verified = mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completedQuantity\":10,\"qualifiedQuantity\":7,\"reworkQuantity\":3,"
                                + "\"scrapQuantity\":0}"))
                .andExpect(status().isOk())
                .andReturn();
        long verificationId = objectMapper.readTree(verified.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        var source = mockMvc.perform(post("/api/rework-sources")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId":%d,"targetNode":"MAKING","quantity":3}
                                """.formatted(verificationId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(source.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private int verifyStatus(long planId, int completed) {
        try {
            return mockMvc.perform(post("/api/production-plans/" + planId + "/verify")
                            .cookie(sessionCookie).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"completedQuantity":%d,"qualifiedQuantity":%d,"reworkQuantity":0,
                                     "scrapQuantity":0}
                                    """.formatted(completed, completed)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private List<Integer> runConcurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = pool.invokeAll(List.of(first, second), 30, TimeUnit.SECONDS);
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            pool.shutdownNow();
        }
    }

    private long createNormalPlan(String node, long employeeId, LocalDate planDate, int quantity) throws Exception {
        var created = mockMvc.perform(post("/api/production-plans")
                        .cookie(sessionCookie).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planType":"NORMAL","orderItemId":%d,"node":"%s","planDate":"%s",
                                 "employeeId":%d,"quantity":%d}
                                """.formatted(orderItemId, node, planDate, employeeId, quantity)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
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

    private int planCount() {
        var value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM production_plans WHERE order_id = ?", Integer.class, orderId);
        return value == null ? 0 : value;
    }

    private String key() {
        return java.util.UUID.randomUUID().toString();
    }
}
