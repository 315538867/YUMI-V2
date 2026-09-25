package com.yumi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务 5.1 生产模块迁移断言：十张表、PN/OS 唯一编号、唯一核验、来源余额、
 * 超额预占配对与数量、其他排班分钟口径、提醒类型与状态；Hibernate {@code ddl-auto: validate}
 * 由上下文启动保证。CHECK 违反在 MySQL 报 error 3819，统一断言 {@link DataAccessException} + 约束名。
 */
@SpringBootTest
class ProductionMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private long orderId;
    private long orderItemId;
    private long employeeId;

    @BeforeEach
    void setup() {
        cleanup();
        jdbcTemplate.update("""
                INSERT INTO products (product_no, name, status, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, total_cost, version, created_at, updated_at)
                VALUES ('TN0001', 'TST-生产迁移商品', 'ACTIVE', (SELECT MIN(id) FROM star_levels), '一星', 5,
                    10.0000, 100, 6.0000, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long productId = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = 'TN0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO customers (customer_no, name, version, created_at, updated_at)
                VALUES ('TN0001', 'TST-生产迁移客户', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM customers WHERE customer_no = 'TN0001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO orders (order_no, customer_id, customer_name, status, order_date, recipient_name,
                    recipient_phone, region, address, goods_amount, seam_amount, discount_amount,
                    receivable_amount, goods_cost_amount, seam_cost_amount, cost_amount, profit_amount,
                    version, created_at, updated_at)
                VALUES ('TN00001', ?, 'TST-生产迁移客户', 'CONFIRMED', '2026-09-25', '收货人', '13800000000',
                    '华东', '地址', 100.0000, 0.0000, 0.0000, 100.0000, 60.0000, 0.0000, 60.0000, 40.0000,
                    1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, customerId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_no = 'TN00001'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_items (order_id, line_no, product_id, product_no, product_name,
                    quantity, seam_quantity, unit_price, goods_amount, unit_cost, goods_cost_amount,
                    version, created_at, updated_at)
                VALUES (?, 1, ?, 'TN0001', 'TST-生产迁移商品', 10, 0, 10.0000, 100.0000, 6.0000, 60.0000,
                    0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, productId);
        orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND line_no = 1", Long.class, orderId);
        jdbcTemplate.update("""
                INSERT INTO employees (employee_no, name, status, version, created_at, updated_at)
                VALUES ('TE0001', 'TST-生产迁移员工', 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """);
        employeeId = jdbcTemplate.queryForObject(
                "SELECT id FROM employees WHERE employee_no = 'TE0001'", Long.class);
    }

    @AfterEach
    void cleanup() {
        var testPlans = "SELECT id FROM production_plans WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_no = 'TN00001')";
        jdbcTemplate.update("DELETE FROM production_reminders WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_no = 'TN00001')");
        jdbcTemplate.update("DELETE FROM overtime_preemptions WHERE overtime_plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM remake_sources WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_no = 'TN00001')");
        jdbcTemplate.update("DELETE FROM rework_sources WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_no = 'TN00001')");
        jdbcTemplate.update("DELETE FROM production_verifications WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plan_adjustments WHERE plan_id IN (" + testPlans + ")");
        jdbcTemplate.update("DELETE FROM production_plans WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_no = 'TN00001')");
        jdbcTemplate.update("DELETE FROM other_schedule_time_corrections WHERE schedule_id IN "
                + "(SELECT id FROM other_schedules WHERE schedule_no LIKE 'OS9%')");
        jdbcTemplate.update("DELETE FROM other_schedule_verifications WHERE schedule_id IN "
                + "(SELECT id FROM other_schedules WHERE schedule_no LIKE 'OS9%')");
        jdbcTemplate.update("DELETE FROM other_schedules WHERE schedule_no LIKE 'OS9%'");
        jdbcTemplate.update("DELETE FROM order_items WHERE order_id IN "
                + "(SELECT id FROM orders WHERE order_no = 'TN00001')");
        jdbcTemplate.update("DELETE FROM orders WHERE order_no = 'TN00001'");
        jdbcTemplate.update("DELETE FROM customers WHERE customer_no = 'TN0001'");
        jdbcTemplate.update("DELETE FROM products WHERE product_no = 'TN0001'");
        jdbcTemplate.update("DELETE FROM employees WHERE employee_no = 'TE0001'");
    }

    @Test
    void createsProductionTables() {
        for (var table : new String[]{"production_plans", "production_plan_adjustments",
                "production_verifications", "rework_sources", "remake_sources", "overtime_preemptions",
                "production_reminders", "other_schedules", "other_schedule_verifications",
                "other_schedule_time_corrections"}) {
            assertThat(tableExists(table)).as("缺少表 " + table).isTrue();
        }
    }

    @Test
    void enforcesPlanNumbersKeysAndReferences() {
        assertThat(columnType("production_plans", "plan_no")).isEqualTo("char(8)");
        assertThat(columnType("production_plans", "quantity")).isEqualTo("int unsigned");
        assertThat(columnType("production_plans", "plan_date")).isEqualTo("date");
        assertThat(columnType("other_schedules", "schedule_no")).isEqualTo("char(8)");
        assertThat(columnType("other_schedules", "total_minutes")).isEqualTo("int unsigned");

        assertThat(uniqueKeys("production_plans")).contains("uk_production_plans_plan_no");
        assertThat(uniqueKeys("production_verifications")).contains("uk_production_verifications_plan");
        assertThat(uniqueKeys("rework_sources")).contains("uk_rework_sources_target");
        assertThat(uniqueKeys("remake_sources")).contains("uk_remake_sources_target");
        assertThat(uniqueKeys("overtime_preemptions")).contains("uk_overtime_preemptions_pair");
        assertThat(uniqueKeys("other_schedules")).contains("uk_other_schedules_schedule_no");
        assertThat(uniqueKeys("other_schedule_verifications")).contains("uk_other_schedule_verifications_schedule");

        assertThat(indexes("production_plans")).contains("idx_production_plans_date_node",
                "idx_production_plans_employee", "idx_production_plans_item", "idx_production_plans_source");
        assertThat(indexes("overtime_preemptions")).contains("idx_overtime_preemptions_future");
        assertThat(indexes("production_reminders")).contains("idx_production_reminders_type_status");

        assertThat(foreignKeys("production_plans")).contains("fk_production_plans_order",
                "fk_production_plans_item", "fk_production_plans_employee");
        assertThat(foreignKeys("production_verifications")).contains("fk_production_verifications_plan",
                "fk_production_verifications_order", "fk_production_verifications_item");
        assertThat(foreignKeys("rework_sources")).contains("fk_rework_sources_verification",
                "fk_rework_sources_item", "fk_rework_sources_previous");
        assertThat(foreignKeys("overtime_preemptions")).contains("fk_overtime_preemptions_overtime",
                "fk_overtime_preemptions_future");
        assertThat(foreignKeys("production_reminders")).contains("fk_production_reminders_plan",
                "fk_production_reminders_verification", "fk_production_reminders_preemption",
                "fk_production_reminders_future_plan");
        assertThat(foreignKeys("other_schedules")).contains("fk_other_schedules_employee");
        assertThat(foreignKeys("other_schedule_time_corrections")).contains(
                "fk_other_schedule_time_corrections_verification", "fk_other_schedule_time_corrections_schedule");
    }

    @Test
    void rejectsDuplicatePlanNumberAndBadTypeOrStatus() {
        insertPlan("PN900001", "NORMAL", "MAKING", 5, "ORDER");
        assertThatThrownBy(() -> insertPlan("PN900001", "NORMAL", "MAKING", 5, "ORDER"))
                .as("计划编号唯一")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_production_plans_plan_no");
        assertThatThrownBy(() -> insertPlan("PN900002", "UNKNOWN_TYPE", "MAKING", 5, "ORDER"))
                .as("计划类型受约束")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_plans_type");
        assertThatThrownBy(() -> insertPlan("PN900003", "NORMAL", "MAKING", 0, "ORDER"))
                .as("计划数量必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_plans_quantity");
    }

    @Test
    void enforcesVerificationEquationAndSingleVerificationPerPlan() {
        var planId = insertPlan("PN900010", "NORMAL", "MAKING", 10, "ORDER");

        // 本次完成 ≠ 合格 + 返工 + 报废
        assertThatThrownBy(() -> insertVerification(planId, 10, 6, 2, 1, 0))
                .as("核验等式必须成立")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_verifications_equation");

        insertVerification(planId, 10, 6, 3, 1, 0);
        assertThatThrownBy(() -> insertVerification(planId, 8, 8, 0, 0, 2))
                .as("每计划最多一次有效核验")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_production_verifications_plan");
    }

    @Test
    void enforcesReworkAndRemakeSourceBalances() {
        var planId = insertPlan("PN900020", "NORMAL", "PACKING_BAG", 10, "ORDER");
        var verificationId = insertVerification(planId, 10, 7, 3, 0, 0);

        insertReworkSource(verificationId, "PACKING_BAG", "MAKING", 3, 0);
        assertThatThrownBy(() -> insertReworkSource(verificationId, "PACKING_BAG", "MAKING", 1, 0))
                .as("同一核验的同一目标工序只有一条来源")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_rework_sources_target");
        assertThatThrownBy(() -> insertReworkSource(verificationId, "PACKING_BAG", "PACKING_BAG", 3, 4))
                .as("已安排不得超过总量")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_rework_sources_quantity");

        // 重做来源：同一核验同一起始工序只一条，数量自洽
        insertRemakeSource(verificationId, "SEAM_CUTTING", "SEAM_CUTTING", 2, 0);
        assertThatThrownBy(() -> insertRemakeSource(verificationId, "SEAM_CUTTING", "SEAM_CUTTING", 1, 0))
                .as("同一核验同一起始工序只形成一条重做来源")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_remake_sources_target");
        assertThatThrownBy(() -> insertRemakeSource(verificationId, "SEAM_CUTTING", "SEAM_CUTTING", 0, 0))
                .as("重做总量必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_remake_sources_quantity");
    }

    @Test
    void enforcesOvertimePreemptionQuantityAndPair() {
        var futurePlan = insertPlan("PN900030", "NORMAL", "MAKING", 10, "ORDER");
        var overtimePlan = insertPlan("PN900031", "OVERTIME", "MAKING", 4, "NONE");

        insertPreemption(overtimePlan, futurePlan, 4);
        assertThatThrownBy(() -> insertPreemption(overtimePlan, futurePlan, 1))
                .as("同一超额任务对同一未来计划只有一条预占")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_overtime_preemptions_pair");
        assertThatThrownBy(() -> insertPreemption(overtimePlan, futurePlan, 0))
                .as("预占数量必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_overtime_preemptions_quantity");
    }

    @Test
    void enforcesOtherScheduleMinutesAndSingleVerification() {
        // 分钟必须 0–59
        assertThatThrownBy(() -> insertSchedule("OS900001", 1, 60, 120))
                .as("分钟必须在 0 与 59 之间")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_other_schedules_minutes");
        // 总分钟必须等于 小时 × 60 + 分钟
        assertThatThrownBy(() -> insertSchedule("OS900002", 1, 30, 100))
                .as("总分钟口径必须自洽")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_other_schedules_total");
        // 总分钟必须大于 0
        assertThatThrownBy(() -> insertSchedule("OS900003", 0, 0, 0))
                .as("总分钟必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_other_schedules_total");

        var scheduleId = insertSchedule("OS900004", 2, 15, 135);
        insertScheduleVerification(scheduleId, 135);
        assertThatThrownBy(() -> insertScheduleVerification(scheduleId, 120))
                .as("每排班最多一次工时核验")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_other_schedule_verifications_schedule");
        assertThatThrownBy(() -> insertScheduleVerification(scheduleId, 0))
                .as("核验总分钟必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_other_schedule_verifications_total");

        // 工时更正：追加事实，不修改原核验
        var verificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM other_schedule_verifications WHERE schedule_id = ?", Long.class, scheduleId);
        jdbcTemplate.update("""
                INSERT INTO other_schedule_time_corrections (verification_id, schedule_id, before_total_minutes,
                    after_total_minutes, reason, created_at, updated_at)
                VALUES (?, ?, 135, 120, '录错分钟', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, verificationId, scheduleId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO other_schedule_time_corrections (verification_id, schedule_id, before_total_minutes,
                    after_total_minutes, reason, created_at, updated_at)
                VALUES (?, ?, 120, 0, '录错分钟', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, verificationId, scheduleId))
                .as("更正后的总分钟必须大于 0")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_other_schedule_time_corrections_after");
    }

    @Test
    void enforcesReminderTypeAndStatus() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO production_reminders (reminder_type, order_id, order_item_id, node, quantity,
                    status, created_at, updated_at)
                VALUES ('UNKNOWN_TYPE', ?, ?, 'MAKING', 3, 'OPEN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId))
                .as("提醒类型受约束")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_reminders_type");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO production_reminders (reminder_type, order_id, order_item_id, node, quantity,
                    status, created_at, updated_at)
                VALUES ('INCOMPLETE', ?, ?, 'MAKING', 3, 'DONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orderId, orderItemId))
                .as("提醒状态受约束")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_production_reminders_status");
    }

    // ---------- 内部工具 ----------

    private long insertPlan(String planNo, String type, String node, int quantity, String sourceType) {
        jdbcTemplate.update("""
                INSERT INTO production_plans (plan_no, plan_type, order_id, order_item_id, node, plan_date,
                    employee_id, employee_name, quantity, status, source_type, source_id, source_line_id,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '2026-09-25', ?, 'TST-生产迁移员工', ?, 'PENDING', ?, 0, 0,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, planNo, type, orderId, orderItemId, node, employeeId, quantity, sourceType);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_plans WHERE plan_no = ?", Long.class, planNo);
    }

    private long insertVerification(long planId, int completed, int qualified, int rework, int scrap,
                                    int incomplete) {
        jdbcTemplate.update("""
                INSERT INTO production_verifications (plan_id, order_id, order_item_id, node,
                    completed_quantity, qualified_quantity, rework_quantity, scrap_quantity,
                    incomplete_quantity, verified_by, verified_at, created_at, updated_at)
                VALUES (?, ?, ?, 'MAKING', ?, ?, ?, ?, ?, 'tester', UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, planId, orderId, orderItemId, completed, qualified, rework, scrap, incomplete);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM production_verifications WHERE plan_id = ?", Long.class, planId);
    }

    private void insertReworkSource(long verificationId, String foundNode, String targetNode, int total,
                                    int arranged) {
        jdbcTemplate.update("""
                INSERT INTO rework_sources (verification_id, order_id, order_item_id, found_node, target_node,
                    total_quantity, arranged_quantity, round_no, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, verificationId, orderId, orderItemId, foundNode, targetNode, total, arranged);
    }

    private void insertRemakeSource(long verificationId, String scrapNode, String startNode, int total,
                                    int arranged) {
        jdbcTemplate.update("""
                INSERT INTO remake_sources (verification_id, order_id, order_item_id, scrap_node, start_node,
                    total_quantity, arranged_quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, verificationId, orderId, orderItemId, scrapNode, startNode, total, arranged);
    }

    private void insertPreemption(long overtimePlanId, long futurePlanId, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO overtime_preemptions (overtime_plan_id, future_plan_id, order_id, order_item_id,
                    node, preempted_quantity, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MAKING', ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, overtimePlanId, futurePlanId, orderId, orderItemId, quantity);
    }

    private long insertSchedule(String scheduleNo, int hours, int minutes, int totalMinutes) {
        jdbcTemplate.update("""
                INSERT INTO other_schedules (schedule_no, schedule_date, employee_id, employee_name, hours,
                    minutes, total_minutes, status, created_at, updated_at)
                VALUES (?, '2026-09-25', ?, 'TST-生产迁移员工', ?, ?, ?, 'PENDING',
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, scheduleNo, employeeId, hours, minutes, totalMinutes);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM other_schedules WHERE schedule_no = ?", Long.class, scheduleNo);
    }

    private void insertScheduleVerification(long scheduleId, int totalMinutes) {
        jdbcTemplate.update("""
                INSERT INTO other_schedule_verifications (schedule_id, total_minutes, verified_by, verified_at,
                    created_at, updated_at)
                VALUES (?, ?, 'tester', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, scheduleId, totalMinutes);
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Boolean.class, tableName));
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, tableName, columnName);
    }

    private java.util.List<String> uniqueKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND non_unique = 0 "
                        + "AND index_name <> 'PRIMARY'",
                String.class, tableName);
    }

    private java.util.List<String> indexes(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class, tableName);
    }

    private java.util.List<String> foreignKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND constraint_type = 'FOREIGN KEY'",
                String.class, tableName);
    }
}
