package com.yumi.production.application.port;

import com.yumi.production.api.SubjectRef;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.api.TaskType;
import java.time.Instant;
import java.time.LocalDate;

/** 作业任务头记录（模块内部类型）。 */
public record TaskRecord(
        long id,
        String taskBusinessId,
        String taskNo,
        TaskType taskType,
        LocalDate workDate,
        TargetProcess processCode,
        int plannedWorkQuantity,
        Integer enteredStandardMinutes,
        long taskStandardSeconds,
        SubjectRef subject,
        String subjectSnapshotRef,
        String assessmentBusinessId,
        String workTimeStandardSnapshotRef,
        String efficiencySnapshotRef,
        TaskStatus status,
        long activeAssignmentVersion,
        long rowVersion,
        Instant createdAt,
        String createdBy,
        Instant updatedAt) {
}
