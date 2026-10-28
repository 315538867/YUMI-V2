package com.yumi.workforce.api;

import java.util.Optional;

/** 跨模块只读员工目录：为财务等下游提供稳定标识对应的编号与姓名。 */
public interface WorkforceEmployeeDirectory {

    Optional<EmployeeDirectoryEntry> find(String employeeBusinessId);

    /** 员工目录条目。 */
    record EmployeeDirectoryEntry(
            String employeeBusinessId, String employeeNo, String name) {
    }
}
