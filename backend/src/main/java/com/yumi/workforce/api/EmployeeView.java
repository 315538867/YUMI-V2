package com.yumi.workforce.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 员工当前资料与资格。
 *
 * <p>一期没有账号绑定：本视图与命令都不含账号、角色或登录名。
 */
public record EmployeeView(
        String employeeBusinessId,
        String employeeNo,
        String name,
        String phone,
        EmploymentStatus employmentStatus,
        LocalDate latestEffectiveDate,
        long contentVersion,
        long rowVersion,
        List<QualificationView> qualifications,
        Instant updatedAt) {

    public EmployeeView {
        qualifications = List.copyOf(qualifications);
    }

    /** 工种资格视图。 */
    public record QualificationView(
            String workTypeCode, QualificationStatus status, LocalDate effectiveFrom) {
    }
}
