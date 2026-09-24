package com.yumi.catalog.employee.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 员工工种仓库：查询、整组替换（先删后插）与资格存在性判断。
 */
public interface EmployeeWorkTypeRepository extends JpaRepository<EmployeeWorkType, Long> {

    List<EmployeeWorkType> findByEmployeeIdOrderByIdAsc(Long employeeId);

    List<EmployeeWorkType> findByEmployeeIdIn(Collection<Long> employeeIds);

    boolean existsByEmployeeIdAndWorkTypeId(Long employeeId, Long workTypeId);

    void deleteByEmployeeId(Long employeeId);
}
