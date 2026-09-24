package com.yumi.catalog.employee.dto;

/**
 * 员工工种视图（任务 2.26）：接口按系统固定 code 出入参，展示使用名称。
 */
public record WorkTypeView(String code, String name) {
}
