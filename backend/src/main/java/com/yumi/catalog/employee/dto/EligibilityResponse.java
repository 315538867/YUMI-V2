package com.yumi.catalog.employee.dto;

/**
 * 工种资格查询成功形态；不合格时不返回此结构，而是 409 EMPLOYEE_NOT_ELIGIBLE（data=null）。
 */
public record EligibilityResponse(boolean eligible, String employeeNo, String name, String workType) {
}
