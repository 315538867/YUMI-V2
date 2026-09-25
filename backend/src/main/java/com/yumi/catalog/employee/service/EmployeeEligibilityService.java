package com.yumi.catalog.employee.service;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.internal.Employee;
import com.yumi.catalog.employee.internal.EmployeeRepository;
import com.yumi.catalog.employee.internal.EmployeeWorkTypeRepository;
import com.yumi.catalog.employee.internal.WorkTypeReference;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工种资格核验公开服务（阶段五复用入口）：在职且具备该工种才通过，
 * 否则抛 EMPLOYEE_NOT_ELIGIBLE（HTTP 409，data=null）；通过返回员工快照。
 * 工种按系统固定 code 出入参（任务 2.26）。
 */
@Service
public class EmployeeEligibilityService {

    private final EmployeeRepository employees;
    private final EmployeeWorkTypeRepository workTypes;
    private final WorkTypeReference workTypeReference;

    public EmployeeEligibilityService(EmployeeRepository employees, EmployeeWorkTypeRepository workTypes,
                                      WorkTypeReference workTypeReference) {
        this.employees = employees;
        this.workTypes = workTypes;
        this.workTypeReference = workTypeReference;
    }

    /**
     * @throws ApiException VALIDATION_INVALID  workType 缺失或不是系统内置工种
     * @throws ApiException NOT_FOUND           员工不存在
     * @throws ApiException EMPLOYEE_NOT_ELIGIBLE 员工离职或不具备该工种
     */
    @Transactional(readOnly = true)
    public EmployeeSnapshot checkEligible(Long employeeId, String workType) {
        var type = workTypeReference.byCode(workType)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_INVALID, "workType 缺失或取值不合法",
                        List.of(new ApiFieldError("workType", "仅支持系统内置工种"))));
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "员工不存在"));
        if (!Employee.STATUS_ACTIVE.equals(employee.getStatus())
                || !workTypes.existsByEmployeeIdAndWorkTypeId(employeeId, type.id())) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ELIGIBLE);
        }
        return new EmployeeSnapshot(employee.getEmployeeNo(), employee.getName());
    }

    /**
     * 只校验在职（其他排班不绑定工序，工种校验无意义）：离职返回 {@code EMPLOYEE_NOT_ELIGIBLE}。
     *
     * @throws ApiException NOT_FOUND           员工不存在
     * @throws ApiException EMPLOYEE_NOT_ELIGIBLE 员工离职
     */
    @Transactional(readOnly = true)
    public EmployeeSnapshot requireActive(Long employeeId) {
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "员工不存在"));
        if (!Employee.STATUS_ACTIVE.equals(employee.getStatus())) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_ELIGIBLE);
        }
        return new EmployeeSnapshot(employee.getEmployeeNo(), employee.getName());
    }
}
