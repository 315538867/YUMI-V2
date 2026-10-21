package com.yumi.workforce.api;

/** 员工引用。 */
public record EmployeeRef(
        String employeeBusinessId, String employeeNo, EmploymentStatus employmentStatus,
        long contentVersion, long rowVersion) {
}
