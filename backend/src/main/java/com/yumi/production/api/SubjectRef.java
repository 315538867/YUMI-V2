package com.yumi.production.api;

/** 执行主体引用：员工或小组。 */
public record SubjectRef(SubjectType subjectType, String subjectBusinessId) {

    public SubjectRef {
        if (subjectType == null || subjectBusinessId == null || subjectBusinessId.isBlank()) {
            throw new IllegalArgumentException("执行主体类型与稳定标识不能为空");
        }
    }
}
