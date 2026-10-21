package com.yumi.workforce.application.port;

import com.yumi.workforce.api.EmploymentStatus;
import java.time.Instant;

/** 员工当前记录（模块内部类型）。 */
public record EmployeeRecord(
        long id,
        String employeeBusinessId,
        String employeeNo,
        String name,
        String phone,
        EmploymentStatus employmentStatus,
        long contentVersion,
        long rowVersion,
        Instant createdAt,
        Instant updatedAt) {
}
