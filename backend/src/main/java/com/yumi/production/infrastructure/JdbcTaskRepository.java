package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.SubjectType;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import com.yumi.production.application.port.TaskItemRecord;
import com.yumi.production.application.port.TaskRecord;
import com.yumi.production.application.port.TaskRepository;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 作业任务持久化（MySQL）。 */
@Repository
public class JdbcTaskRepository implements TaskRepository {

    private final JdbcClient jdbc;

    public JdbcTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TaskRecord> findTask(String taskBusinessId) {
        return jdbc.sql(TASK_SELECT + " WHERE task_business_id = :id")
                .param("id", taskBusinessId)
                .query(JdbcTaskRepository::mapTask)
                .optional();
    }

    @Override
    public Optional<TaskRecord> lockTask(String taskBusinessId) {
        return jdbc.sql(TASK_SELECT + " WHERE task_business_id = :id FOR UPDATE")
                .param("id", taskBusinessId)
                .query(JdbcTaskRepository::mapTask)
                .optional();
    }

    @Override
    public void updateTaskStatus(String taskBusinessId, TaskStatus status) {
        jdbc.sql("""
                        UPDATE production_tasks
                        SET status = :status, row_version = row_version + 1,
                            updated_at = UTC_TIMESTAMP(6)
                        WHERE task_business_id = :id
                        """)
                .param("status", status.name())
                .param("id", taskBusinessId)
                .update();
    }

    @Override
    public List<TaskRecord> findTasksByWorkDate(LocalDate workDate) {
        return jdbc.sql(TASK_SELECT + " WHERE work_date = :workDate ORDER BY task_no")
                .param("workDate", workDate)
                .query(JdbcTaskRepository::mapTask)
                .list();
    }

    @Override
    public List<TaskItemRecord> findTaskItems(String taskBusinessId) {
        return jdbc.sql(ITEM_SELECT + " WHERE task_business_id = :id ORDER BY item_order")
                .param("id", taskBusinessId)
                .query(JdbcTaskRepository::mapItem)
                .list();
    }

    @Override
    public Optional<TaskItemRecord> findTaskItem(String taskItemBusinessId) {
        return jdbc.sql(ITEM_SELECT + " WHERE task_item_business_id = :id")
                .param("id", taskItemBusinessId)
                .query(JdbcTaskRepository::mapItem)
                .optional();
    }

    @Override
    public List<String> findParticipantEmployeeIds(String taskBusinessId) {
        return jdbc.sql("""
                        SELECT employee_business_id FROM production_active_participant_references
                        WHERE task_business_id = :taskId AND status = 'ACTIVE'
                        ORDER BY employee_business_id
                        """)
                .param("taskId", taskBusinessId)
                .query(String.class)
                .list();
    }

    @Override
    public Optional<TaskRecord> findActiveTaskOfEmployee(
            String employeeBusinessId, LocalDate workDate, TaskType taskType) {
        return jdbc.sql(TASK_SELECT + """
                         WHERE work_date = :workDate AND subject_type = 'EMPLOYEE'
                           AND subject_business_id = :employeeId AND task_type = :taskType
                           AND status <> 'CANCELLED'
                         ORDER BY id LIMIT 1
                        """)
                .param("workDate", Date.valueOf(workDate))
                .param("employeeId", employeeBusinessId)
                .param("taskType", taskType.name())
                .query(JdbcTaskRepository::mapTask)
                .optional();
    }

    @Override
    public List<TaskRecord> findActiveTasksOfEmployee(String employeeBusinessId, LocalDate workDate) {
        return jdbc.sql(TASK_SELECT + """
                         WHERE work_date = :workDate AND subject_type = 'EMPLOYEE'
                           AND subject_business_id = :employeeId AND status <> 'CANCELLED'
                         ORDER BY id
                        """)
                .param("workDate", Date.valueOf(workDate))
                .param("employeeId", employeeBusinessId)
                .query(JdbcTaskRepository::mapTask)
                .list();
    }

    @Override
    public void insertTask(TaskRecord record) {
        jdbc.sql("""
                        INSERT INTO production_tasks (
                            task_business_id, task_no, task_type, work_date, process_code,
                            planned_work_quantity, entered_standard_minutes, task_standard_seconds,
                            subject_type, subject_business_id, subject_snapshot_ref,
                            assessment_business_id, work_time_standard_snapshot_ref,
                            efficiency_snapshot_ref, status, active_assignment_version, row_version,
                            created_at, created_by, updated_at)
                        VALUES (:taskId, :taskNo, :taskType, :workDate, :processCode,
                                :plannedQuantity, :enteredMinutes, :standardSeconds,
                                :subjectType, :subjectId, :subjectSnapshotRef,
                                :assessmentId, :workTimeSnapshotRef, :efficiencySnapshotRef,
                                :status, 1, 1, :now, :createdBy, :now)
                        """)
                .param("taskId", record.taskBusinessId())
                .param("taskNo", record.taskNo())
                .param("taskType", record.taskType().name())
                .param("workDate", Date.valueOf(record.workDate()))
                .param("processCode", record.processCode() == null ? null : record.processCode().name())
                .param("plannedQuantity", record.plannedWorkQuantity())
                .param("enteredMinutes", record.enteredStandardMinutes())
                .param("standardSeconds", record.taskStandardSeconds())
                .param("subjectType", record.subject().subjectType().name())
                .param("subjectId", record.subject().subjectBusinessId())
                .param("subjectSnapshotRef", record.subjectSnapshotRef())
                .param("assessmentId", record.assessmentBusinessId())
                .param("workTimeSnapshotRef", record.workTimeStandardSnapshotRef())
                .param("efficiencySnapshotRef", record.efficiencySnapshotRef())
                .param("status", record.status().name())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .param("createdBy", record.createdBy())
                .update();
    }

    @Override
    public void insertTaskItem(TaskItemRecord record) {
        jdbc.sql("""
                        INSERT INTO production_task_items (
                            task_item_business_id, task_business_id, item_order, demand_business_id,
                            demand_type, production_source_business_id, physical_root_source_business_id,
                            repair_source_business_id, order_business_id, order_item_business_id,
                            product_business_id, product_snapshot_ref, scheme_business_id,
                            scheme_snapshot_ref, target_process, planned_quantity, item_standard_minutes,
                            verification_status, assignment_version, row_version)
                        VALUES (:itemId, :taskId, :itemOrder, :demandId, :demandType, :sourceId,
                                :physicalRoot, :repairSourceId, :orderId, :orderItemId, :productId,
                                :productSnapshotRef, :schemeId, :schemeSnapshotRef, :targetProcess,
                                :plannedQuantity, :standardMinutes, :verificationStatus, 1, 1)
                        """)
                .param("itemId", record.taskItemBusinessId())
                .param("taskId", record.taskBusinessId())
                .param("itemOrder", record.itemOrder())
                .param("demandId", record.demandBusinessId())
                .param("demandType", record.demandType().name())
                .param("sourceId", record.productionSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("repairSourceId", record.repairSourceBusinessId())
                .param("orderId", record.orderBusinessId())
                .param("orderItemId", record.orderItemBusinessId())
                .param("productId", record.productBusinessId())
                .param("productSnapshotRef", record.productSnapshotRef())
                .param("schemeId", record.schemeBusinessId())
                .param("schemeSnapshotRef", record.schemeSnapshotRef())
                .param("targetProcess", record.targetProcess().name())
                .param("plannedQuantity", record.plannedQuantity())
                .param("standardMinutes", record.itemStandardMinutes())
                .param("verificationStatus", record.verificationStatus())
                .update();
    }

    @Override
    public void insertAssignmentFact(
            String taskBusinessId, long assignmentVersion, String operationType,
            String newSubjectRef, LocalDate newWorkDate, String newProcessCode, Integer newQuantity,
            Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO production_task_assignment_facts (
                            fact_business_id, task_business_id, assignment_version, operation_type,
                            old_subject_ref, new_subject_ref, old_work_date, new_work_date,
                            old_process_code, new_process_code, old_quantity, new_quantity,
                            occurred_at, operator_id, is_valid)
                        VALUES (:factId, :taskId, :version, :operationType, NULL, :subjectRef,
                                NULL, :workDate, NULL, :processCode, NULL, :quantity, :now, :operatorId, 1)
                        """)
                .param("factId", "TAF-" + java.util.UUID.randomUUID())
                .param("taskId", taskBusinessId)
                .param("version", assignmentVersion)
                .param("operationType", operationType)
                .param("subjectRef", newSubjectRef)
                .param("workDate", Date.valueOf(newWorkDate))
                .param("processCode", newProcessCode)
                .param("quantity", newQuantity)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public void insertItemAssignmentFact(
            TaskItemRecord item, long assignmentVersion, String operationType, Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO production_task_item_assignment_facts (
                            fact_business_id, task_business_id, task_item_business_id, assignment_version,
                            item_order, demand_business_id, production_source_business_id,
                            order_business_id, order_item_business_id, product_business_id,
                            product_snapshot_ref, scheme_business_id, scheme_snapshot_ref, target_process,
                            planned_quantity, item_standard_minutes, operation_type, occurred_at,
                            operator_id, is_valid)
                        VALUES (:factId, :taskId, :itemId, :version, :itemOrder, :demandId, :sourceId,
                                :orderId, :orderItemId, :productId, :productSnapshotRef, :schemeId,
                                :schemeSnapshotRef, :targetProcess, :plannedQuantity, :standardMinutes,
                                :operationType, :now, :operatorId, 1)
                        """)
                .param("factId", "TIA-" + java.util.UUID.randomUUID())
                .param("taskId", item.taskBusinessId())
                .param("itemId", item.taskItemBusinessId())
                .param("version", assignmentVersion)
                .param("itemOrder", item.itemOrder())
                .param("demandId", item.demandBusinessId())
                .param("sourceId", item.productionSourceBusinessId())
                .param("orderId", item.orderBusinessId())
                .param("orderItemId", item.orderItemBusinessId())
                .param("productId", item.productBusinessId())
                .param("productSnapshotRef", item.productSnapshotRef())
                .param("schemeId", item.schemeBusinessId())
                .param("schemeSnapshotRef", item.schemeSnapshotRef())
                .param("targetProcess", item.targetProcess().name())
                .param("plannedQuantity", item.plannedQuantity())
                .param("standardMinutes", item.itemStandardMinutes())
                .param("operationType", operationType)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .update();
    }

    private static final String TASK_SELECT = """
            SELECT id, task_business_id, task_no, task_type, work_date, process_code,
                   planned_work_quantity, entered_standard_minutes, task_standard_seconds,
                   subject_type, subject_business_id, subject_snapshot_ref, assessment_business_id,
                   work_time_standard_snapshot_ref, efficiency_snapshot_ref, status,
                   active_assignment_version, row_version, created_at, created_by, updated_at
            FROM production_tasks
            """;

    private static final String ITEM_SELECT = """
            SELECT task_item_business_id, task_business_id, item_order, demand_business_id, demand_type,
                   production_source_business_id, physical_root_source_business_id, repair_source_business_id,
                   order_business_id, order_item_business_id, product_business_id, product_snapshot_ref,
                   scheme_business_id, scheme_snapshot_ref, target_process, planned_quantity,
                   item_standard_minutes, verification_status, assignment_version, row_version
            FROM production_task_items
            """;

    private static TaskRecord mapTask(ResultSet resultSet, int rowNumber) throws SQLException {
        String processCode = resultSet.getString("process_code");
        Integer enteredMinutes = resultSet.getObject("entered_standard_minutes") == null
                ? null : resultSet.getInt("entered_standard_minutes");
        return new TaskRecord(
                resultSet.getLong("id"),
                resultSet.getString("task_business_id"),
                resultSet.getString("task_no"),
                TaskType.valueOf(resultSet.getString("task_type")),
                resultSet.getObject("work_date", LocalDate.class),
                processCode == null ? null : TargetProcess.valueOf(processCode),
                resultSet.getInt("planned_work_quantity"),
                enteredMinutes,
                resultSet.getLong("task_standard_seconds"),
                new SubjectRef(
                        SubjectType.valueOf(resultSet.getString("subject_type")),
                        resultSet.getString("subject_business_id")),
                resultSet.getString("subject_snapshot_ref"),
                resultSet.getString("assessment_business_id"),
                resultSet.getString("work_time_standard_snapshot_ref"),
                resultSet.getString("efficiency_snapshot_ref"),
                TaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("active_assignment_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                resultSet.getString("created_by"),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)));
    }

    private static TaskItemRecord mapItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TaskItemRecord(
                resultSet.getString("task_item_business_id"),
                resultSet.getString("task_business_id"),
                resultSet.getInt("item_order"),
                resultSet.getString("demand_business_id"),
                com.yumi.production.api.DemandType.valueOf(resultSet.getString("demand_type")),
                resultSet.getString("production_source_business_id"),
                resultSet.getString("physical_root_source_business_id"),
                resultSet.getString("repair_source_business_id"),
                resultSet.getString("order_business_id"),
                resultSet.getString("order_item_business_id"),
                resultSet.getString("product_business_id"),
                resultSet.getString("product_snapshot_ref"),
                resultSet.getString("scheme_business_id"),
                resultSet.getString("scheme_snapshot_ref"),
                TargetProcess.valueOf(resultSet.getString("target_process")),
                resultSet.getInt("planned_quantity"),
                resultSet.getInt("item_standard_minutes"),
                resultSet.getString("verification_status"),
                resultSet.getLong("assignment_version"),
                resultSet.getLong("row_version"));
    }
}
