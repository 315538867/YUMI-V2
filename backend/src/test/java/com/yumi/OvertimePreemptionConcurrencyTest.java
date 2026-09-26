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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段五 5.14 超额预占的并发门禁：并发预占同一未来明细时恰好一笔成功，
 * 有效预占合计不得超过未来明细可被预占的余额；同一超额明细与未来明细的组合不可重复。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OvertimePreemptionConcurrencyTest {

    private static final String USERNAME = "overtime-race-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
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
    private long makerId;
    private long futureItemId;
    private final LocalDate today = LocalDate.now();
    private final LocalDate futureDate = LocalDate.now().plusDays(1);

    @BeforeEach
    void setup() throws Exception {
        cleanup();
        fixture = new ProductionFixture(mockMvc, jdbcTemplate, passwordEncoder, objectMapper);
        fixture.insertAdmin(USERNAME, PASSWORD);
        sessionCookie = fixture.login(USERNAME, PASSWORD);
        var customerId = fixture.insertCustomer("TPO101", "TST-超额竞争客户");
        var seamTypeId = fixture.insertSeamType("TST-超额竞争缝边种类", 5);
        var productId = fixture.insertProduct("TPO201", "TST-超额竞争商品", 5, 3, seamTypeId,
                "TST-超额竞争缝边种类", 10, 5);
        var makingWorkTypeId = fixture.workTypeId("MAKING");
        makerId = fixture.insertEmployee("TPO301", "TST-超额竞争制作员", "ACTIVE", "MAKING");
        var orderItemId = fixture.confirmOrder(sessionCookie, customerId, ORDER_DATE,
                new int[][]{{(int) productId, 10, 0}})[0];
        // 未来明细计划 3 件：并发两笔各 2 件只能成功一笔
        var future = fixture.createTask(sessionCookie, futureDate.toString(), makerId, makingWorkTypeId,
                "NORMAL", fixture.orderSourceItem(orderItemId, 3));
        futureItemId = future.path("items").get(0).path("id").asLong();
    }

    @AfterEach
    void cleanup() {
        var testOrders = "SELECT id FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPO101')";
        jdbcTemplate.update("DELETE FROM production_reminders WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM overtime_preemptions WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM overtime_task_items WHERE order_id IN (" + testOrders + ")");
        jdbcTemplate.update("DELETE FROM overtime_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPO301')");
        for (var table : new String[]{"production_quantity_returns", "scrap_records", "rework_sources",
                "production_verifications", "production_task_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM production_tasks WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPO301')");
        for (var table : new String[]{"fulfillment_entries", "order_item_fulfillment_balances",
                "order_item_snapshots", "order_confirmation_snapshots", "order_items"}) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE order_id IN (" + testOrders + ")");
        }
        jdbcTemplate.update("DELETE FROM orders WHERE customer_id IN "
                + "(SELECT id FROM customers WHERE customer_no = 'TPO101')");
        jdbcTemplate.update("DELETE FROM employee_work_types WHERE employee_id IN "
                + "(SELECT id FROM employees WHERE employee_no = 'TPO301')");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TPO301'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no IN ('TPO201')");
        jdbcTemplate.update("DELETE FROM seam_types WHERE name = 'TST-超额竞争缝边种类'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TPO101'");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    private Callable<Integer> reserve(int quantity) {
        return () -> mockMvc.perform(post("/api/overtime-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,
                                 "items":[{"futureTaskItemId":%d,"plannedQuantity":%d}]}
                                """.formatted(today, makerId, futureItemId, quantity)))
                .andReturn().getResponse().getStatus();
    }

    private int activePreemptedTotal() {
        return fixture.count("""
                SELECT COALESCE(SUM(preempted_quantity), 0) FROM overtime_preemptions
                WHERE future_task_item_id = ? AND status = 'ACTIVE'
                """, futureItemId);
    }

    @Test
    void concurrentReservationsCannotExceedFutureBalance() throws Exception {
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            var futures = List.of(
                    pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return reserve(2).call();
                    }),
                    pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return reserve(2).call();
                    }));
            statuses = List.of(futures.get(0).get(30, TimeUnit.SECONDS),
                    futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(activePreemptedTotal()).isEqualTo(2);
        // 未来原计划数量不变
        assertThat(fixture.count(
                "SELECT planned_quantity FROM production_task_items WHERE id = ?", futureItemId))
                .isEqualTo(3);
    }

    @Test
    void duplicateReservationIsRejected() throws Exception {
        // 未来余额 3：先占满，再预占即被拒
        mockMvc.perform(post("/api/overtime-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,
                                 "items":[{"futureTaskItemId":%d,"plannedQuantity":3}]}
                                """.formatted(today, makerId, futureItemId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/overtime-tasks")
                        .cookie(sessionCookie)
                        .header("Idempotency-Key", ProductionFixture.key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskDate":"%s","employeeId":%d,
                                 "items":[{"futureTaskItemId":%d,"plannedQuantity":1}]}
                                """.formatted(today, makerId, futureItemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERTIME_RESERVATION_EXCEEDED"));
        assertThat(activePreemptedTotal()).isEqualTo(3);

        // 同一超额明细与同一未来明细的组合在数据库层不可重复
        var overtimeItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM overtime_task_items LIMIT 1", Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO overtime_preemptions (overtime_task_item_id, future_task_item_id, order_id,
                    order_item_id, node, preempted_quantity, status, created_at, updated_at)
                SELECT ?, ?, order_id, order_item_id, node, 1, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                FROM overtime_preemptions LIMIT 1
                """, overtimeItemId, futureItemId)).isInstanceOf(Exception.class);
        assertThat(fixture.count("SELECT COUNT(*) FROM overtime_preemptions")).isEqualTo(1);
    }
}
