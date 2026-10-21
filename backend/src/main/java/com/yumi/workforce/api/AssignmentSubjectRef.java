package com.yumi.workforce.api;

/**
 * 执行主体引用：员工或小组。
 *
 * @param subjectType 主体类型
 * @param businessId 员工或小组稳定业务标识
 */
public record AssignmentSubjectRef(AssignmentSubjectType subjectType, String businessId) {

    public AssignmentSubjectRef {
        if (subjectType == null || businessId == null || businessId.isBlank()) {
            throw new IllegalArgumentException("执行主体类型与稳定标识不能为空");
        }
    }
}
