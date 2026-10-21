package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.orders.api.OrderRef;
import com.yumi.production.api.ArrangeTaskCommand;
import com.yumi.production.api.CancelTaskCommand;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskItemRequest;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import com.yumi.support.AbstractOrderFixtureTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生产任务取消：只释放未核验计划占用，保留任务与历史事实。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §7.3、§7.4。真实 MySQL 验证：
 * 来源预留、员工计划负载、商品新制产能与日槽位全部释放；取消事实保留。
 */
class TaskCancellationIT extends AbstractOrderFixtureTest {

    @Test
    @DisplayName("未核验任务整体取消后释放来源、负载、产能与槽位")
    void cancelUnverifiedTaskReleasesAllPlanOccupancy() {
        OrderRef order = confirmOrderForMaking(2);
        String sourceId = orderSourceId(order);
        var employee = hireEmployee("取消任务工人-" + java.util.UUID.randomUUID(), "MAKING");
        var task = asAdmin(() -> orderTaskService.arrange(new ArrangeTaskCommand(
                TaskType.NORMAL_PRODUCTION,
                new SubjectRef(SubjectType.EMPLOYEE, employee.employeeBusinessId()),
                LocalDate.parse("2026-10-06"), TargetProcess.MAKING,
                List.of(new TaskItemRequest(sourceId, 2)), null, null, null)));
        int seconds = (int) task.taskStandardSeconds();
        assertThat(reservedSource(sourceId)).isEqualTo(2);
        assertThat(loadReserved(employee.employeeBusinessId())).isEqualTo(seconds);
        assertThat(capacityReserved()).isEqualTo(2);
        assertThat(slotStatus(employee.employeeBusinessId())).isEqualTo("ACTIVE");

        var cancelled = asAdmin(() -> orderTaskService.cancel(
                new CancelTaskCommand(task.taskBusinessId(), "客户取消")));

        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.plannedWorkQuantity()).isZero();
        assertThat(cancelled.taskStandardSeconds()).isZero();
        assertThat(itemStatus(task.taskBusinessId())).isEqualTo("CANCELLED");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_task_cancellation_facts
                        WHERE task_business_id = :id
                        """).param("id", task.taskBusinessId()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(reservedSource(sourceId)).isZero();
        assertThat(loadReserved(employee.employeeBusinessId())).isZero();
        assertThat(capacityReserved()).isZero();
        assertThat(slotStatus(employee.employeeBusinessId())).isEqualTo("RELEASED");
        assertThat(orderJdbc.sql("""
                        SELECT COUNT(*) FROM production_employee_day_work_modes
                        WHERE employee_business_id = :id AND work_date = '2026-10-06'
                        """).param("id", employee.employeeBusinessId()).query(Long.class).single())
                .isZero();
    }

    private int reservedSource(String sourceId) {
        return orderJdbc.sql("""
                        SELECT reserved_quantity FROM production_source_reservations
                        WHERE production_source_business_id = :id
                        """).param("id", sourceId).query(Integer.class).single();
    }

    private int loadReserved(String employeeId) {
        return orderJdbc.sql("""
                        SELECT reserved_standard_seconds FROM production_employee_day_loads
                        WHERE employee_business_id = :id AND work_date = '2026-10-06'
                        """).param("id", employeeId).query(Integer.class).single();
    }

    private int capacityReserved() {
        return orderJdbc.sql("""
                        SELECT COALESCE(SUM(reserved_new_making_quantity), 0)
                        FROM production_product_day_capacity_usages
                        WHERE work_date = '2026-10-06'
                        """).query(Integer.class).single();
    }

    private String slotStatus(String employeeId) {
        return orderJdbc.sql("""
                        SELECT status FROM production_employee_daily_task_slots
                        WHERE employee_business_id = :id AND work_date = '2026-10-06'
                          AND slot_type = 'NORMAL_PRODUCTION'
                        """).param("id", employeeId).query(String.class).single();
    }

    private String itemStatus(String taskId) {
        return orderJdbc.sql("""
                        SELECT verification_status FROM production_task_items
                        WHERE task_business_id = :id
                        """).param("id", taskId).query(String.class).single();
    }
}
