package com.yumi.production.api;

/** 任务引用。 */
public record TaskRef(
        String taskBusinessId,
        String taskNo,
        TaskType taskType,
        TaskStatus status,
        TargetProcess processCode,
        int plannedWorkQuantity,
        long taskStandardSeconds,
        long assignmentVersion,
        long rowVersion) {
}
