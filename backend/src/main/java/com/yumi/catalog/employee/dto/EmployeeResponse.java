package com.yumi.catalog.employee.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工档案响应：含编号、状态、首次入职日期、工种（code + 名称）与乐观锁版本。
 */
public record EmployeeResponse(Long id, String employeeNo, String name, String phone, String status,
                               LocalDate firstHireDate, String note, List<WorkTypeView> workTypes,
                               Long version) {
}
