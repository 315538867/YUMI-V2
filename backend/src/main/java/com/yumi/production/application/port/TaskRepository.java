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

    /** 行锁读取任务，供取消与调整串行化。 */
    Optional<TaskRecord> lockTask(String taskBusinessId);

    void updateTaskStatus(String taskBusinessId, TaskStatus status);

    /** 按工作日期读取任务（排班页只读）。 */
    List<TaskRecord> findTasksByWorkDate(LocalDate workDate);

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

    void insertTask(TaskRecord record);

    void insertTaskItem(TaskItemRecord record);

    void insertAssignmentFact(
            String taskBusinessId, long assignmentVersion, String operationType,
            String newSubjectRef, LocalDate newWorkDate, String newProcessCode, Integer newQuantity,
            java.time.Instant now, String operatorId);

    void insertItemAssignmentFact(TaskItemRecord item, long assignmentVersion, String operationType,
            java.time.Instant now, String operatorId);
}
