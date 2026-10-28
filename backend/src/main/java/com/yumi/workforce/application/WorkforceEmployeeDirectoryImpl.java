package com.yumi.workforce.application;

import com.yumi.workforce.api.WorkforceEmployeeDirectory;
import com.yumi.workforce.application.port.WorkforceRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 跨模块员工目录实现：只读，不做权限判断，由调用方在自身读权限内使用。 */
@Service
public class WorkforceEmployeeDirectoryImpl implements WorkforceEmployeeDirectory {

    private final WorkforceRepository employees;

    public WorkforceEmployeeDirectoryImpl(WorkforceRepository employees) {
        this.employees = employees;
    }

    @Override
    public Optional<EmployeeDirectoryEntry> find(String employeeBusinessId) {
        return employees.findEmployee(employeeBusinessId)
                .map(record -> new EmployeeDirectoryEntry(
                        record.employeeBusinessId(), record.employeeNo(), record.name()));
    }
}
