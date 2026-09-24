package com.yumi.catalog.employee.dto;

/**
 * 员工资格核验通过后的只读快照，供阶段五派工等场景引用。
 */
public record EmployeeSnapshot(String employeeNo, String name) {
}
