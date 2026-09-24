package com.yumi.catalog.employee.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 雇佣事件仓库：只追加，按插入顺序读取。
 */
public interface EmployeeEmploymentEventRepository extends JpaRepository<EmployeeEmploymentEvent, Long> {

    List<EmployeeEmploymentEvent> findByEmployeeIdOrderByIdAsc(Long employeeId);
}
