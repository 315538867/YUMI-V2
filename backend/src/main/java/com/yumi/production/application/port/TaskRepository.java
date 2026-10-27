package com.yumi.production.application.port;

import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 作业任务持久化端口。 */
public interface TaskRepository {

    Optional<TaskRecord> findTask(String taskBusinessId);

    /** 行锁读取任务，供调整、核验与取消串行化。 */
    Optional<TaskRecord> lockTask(String taskBusinessId);

    void updateTaskStatus(String taskBusinessId, TaskStatus status);

    /** 调整后覆盖任务头计划；调用方已持有任务行锁。 */
    void updateTaskPlan(TaskRecord record);

    /** 按工作日期读取任务（排班页只读）。 */
    List<TaskRecord> findTasksByWorkDate(LocalDate workDate);

    /** 按可选日期区间读取任务；两端为空时读取全部任务（排班周视图/全部列表）。 */
    List<TaskRecord> findTasksBetween(LocalDate startDate, LocalDate endDate);

    List<TaskItemRecord> findTaskItems(String taskBusinessId);

    /** 按明细标识查找（核验只提交明细标识，服务端据此定位任务）。 */
    java.util.Optional<TaskItemRecord> findTaskItem(String taskItemBusinessId);

    /** 任务参与员工：员工任务为本人，小组任务为快照成员。 */
    List<String> findParticipantEmployeeIds(String taskBusinessId);

    /** 同一员工同一工作日期、指定类型、未取消的任务。 */
    Optional<TaskRecord> findActiveTaskOfEmployee(
            String employeeBusinessId, LocalDate workDate, TaskType taskType);

    /** 同一员工同一工作日期所有未取消任务（用于模式与槽位校验）。 */
    List<TaskRecord> findActiveTasksOfEmployee(String employeeBusinessId, LocalDate workDate);

    /**
     * 通过活动参与人引用查找同日其他未取消任务。
     *
     * <p>小组任务的成员不在 {@code subject_business_id} 上，依赖校验必须走参与人引用。
     */
    List<TaskRecord> findOtherActiveTasksOfParticipant(
            String employeeBusinessId, LocalDate workDate, String excludedTaskBusinessId);

    void insertTask(TaskRecord record);

    void insertTaskItem(TaskItemRecord record);

    /** 调整后覆盖未核验明细的来源、数量与标准；调用方已持有任务行锁。 */
    void updateTaskItemPlan(TaskItemRecord record);

    /** 删除未核验明细：计划归零、标记取消并追加减量。 */
    void cancelTaskItem(String taskItemBusinessId, int cancelledQuantity, long assignmentVersion);

    void insertAssignmentFact(
            String taskBusinessId, long assignmentVersion, String operationType,
            String oldSubjectRef, String newSubjectRef, LocalDate oldWorkDate, LocalDate newWorkDate,
            String oldProcessCode, String newProcessCode, Integer oldQuantity, Integer newQuantity,
            java.time.Instant now, String operatorId);

    void insertItemAssignmentFact(TaskItemRecord item, long assignmentVersion, String operationType,
            java.time.Instant now, String operatorId);
}
