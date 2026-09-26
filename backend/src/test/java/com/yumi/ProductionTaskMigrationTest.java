package com.yumi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段五 5.2 迁移断言：生产任务头/明细模型取代计划模型，
 * `REMAKE` 在数据库层不存在可执行契约（无表、无 CHECK 取值、无投影列）。
 */
@SpringBootTest
class ProductionTaskMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    private boolean tableExists(String table) {
        var count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private List<String> columns(String table) {
        return jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                """, String.class, table);
    }

    private List<String> uniqueKeys(String table) {
        return jdbcTemplate.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = DATABASE() AND table_name = ? AND constraint_type = 'UNIQUE'
                """, String.class, table);
    }

    private String checkDefinition(String table, String constraint) {
        return jdbcTemplate.queryForObject("""
                SELECT check_clause FROM information_schema.check_constraints
                WHERE constraint_schema = DATABASE() AND constraint_name = ?
                """, String.class, constraint);
    }

    @Test
    void createsTaskHeaderAndItemTables() {
        for (var table : new String[]{"production_tasks", "production_task_items", "production_verifications",
                "rework_sources", "scrap_records", "production_quantity_returns", "overtime_tasks",
                "overtime_task_items", "overtime_preemptions", "production_reminders"}) {
            assertThat(tableExists(table)).as("缺少表 %s", table).isTrue();
        }
        // 计划模型与重做模型不得残留
        for (var removed : new String[]{"production_plans", "production_plan_adjustments", "remake_sources"}) {
            assertThat(tableExists(removed)).as("已废弃表 %s 仍存在", removed).isFalse();
        }
        assertThat(columns("order_item_fulfillment_balances")).doesNotContain("remake_pending");
    }

    @Test
    void taskHeaderKeepsNoQuantityAggregate() {
        var headerColumns = columns("production_tasks");
        assertThat(headerColumns).contains("task_no", "task_date", "employee_id", "employee_name_snapshot",
                "work_type_id", "work_type_name_snapshot", "task_type", "note");
        // 任务头不参与数量流转：不得保存任何可用于履约、来源或产能计算的汇总列
        assertThat(headerColumns).doesNotContain("quantity", "completed_quantity", "planned_quantity",
                "arranged_quantity", "total_quantity", "node");

        var itemColumns = columns("production_task_items");
        assertThat(itemColumns).contains("planned_quantity", "source_type", "source_id", "standard_minutes",
                "estimated_minutes", "making_effective_hour_rate", "workday_hours", "mold_quantity",
                "daily_batch_limit", "status", "cancel_reason");
        assertThat(uniqueKeys("production_task_items")).contains("uk_production_task_items_source");
    }

    @Test
    void rejectsInvalidTaskType() {
        var taskTypeCheck = checkDefinition("production_tasks", "ck_production_tasks_type");
        assertThat(taskTypeCheck).contains("NORMAL").contains("REWORK").doesNotContain("REMAKE");
        assertThat(taskTypeCheck).doesNotContain("OVERTIME").doesNotContain("AFTER_SALES");

        // 返工/报废/超额/售后都是独立事实边界，不得伪装成生产任务类型
        assertThat(checkDefinition("production_task_items", "ck_production_task_items_source"))
                .contains("ORDER").contains("REWORK_SOURCE").contains("QUANTITY_RETURN")
                .contains("AFTER_SALES_SOURCE").doesNotContain("REMAKE");
        assertThat(checkDefinition("production_task_items", "ck_production_task_items_quantity"))
                .contains("planned_quantity");
        assertThat(checkDefinition("overtime_task_items", "ck_overtime_task_items_status"))
                .contains("PENDING").contains("VERIFIED");
    }

    @Test
    void rejectsDuplicateTaskItem() {
        // 同一任务内同一订单明细、同一工序、同一来源不得重复占用（数据库唯一键兜底）
        assertThat(uniqueKeys("production_task_items"))
                .contains("uk_production_task_items_item_no", "uk_production_task_items_source");
        assertThat(uniqueKeys("production_verifications")).contains("uk_production_verifications_item");
        assertThat(uniqueKeys("production_quantity_returns")).contains("uk_production_quantity_returns_scrap");
        assertThat(uniqueKeys("overtime_preemptions")).contains("uk_overtime_preemptions_pair");
    }
}
