package com.yumi.catalog.employee.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 员工档案仓库：按状态/姓名组合检索，列表按编号排序。
 */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findAllByOrderByEmployeeNoAsc();

    List<Employee> findByStatusOrderByEmployeeNoAsc(String status);

    List<Employee> findByNameLikeOrderByEmployeeNoAsc(String namePattern);

    List<Employee> findByStatusAndNameLikeOrderByEmployeeNoAsc(String status, String namePattern);
}
