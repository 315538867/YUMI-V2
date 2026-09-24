package com.yumi.catalog.employee;

import com.yumi.catalog.employee.dto.EligibilityResponse;
import com.yumi.catalog.employee.dto.EmployeeResponse;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.catalog.employee.service.EmployeeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 2.7/2.8 员工域端点：创建、查询、乐观锁编辑、离职/重入职与工种资格。
 * 无删除端点；成功返回值由统一信封包装为 code=OK,data。
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService employees;
    private final EmployeeEligibilityService eligibility;

    public EmployeeController(EmployeeService employees, EmployeeEligibilityService eligibility) {
        this.employees = employees;
        this.eligibility = eligibility;
    }

    public record CreateRequest(@NotBlank(message = "姓名不能为空") String name,
                                String phone,
                                @NotNull(message = "首次入职日期不能为空") LocalDate firstHireDate,
                                String note,
                                @NotNull(message = "工种不能为空") List<String> workTypes) {
    }

    public record PatchRequest(@NotNull(message = "version 必填") Long version,
                               String name, String phone, String note, List<String> workTypes) {
    }

    public record LeaveRequest(@NotBlank(message = "离职原因必填") String reason,
                               @NotNull(message = "离职日期不能为空") LocalDate date) {
    }

    public record RehireRequest(@NotNull(message = "重入职日期不能为空") LocalDate date) {
    }

    @PostMapping
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody CreateRequest request) {
        var created = employees.create(new EmployeeService.CreateCommand(
                request.name(), request.phone(), request.firstHireDate(), request.note(), request.workTypes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<EmployeeResponse> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String name) {
        return employees.list(status, name);
    }

    @GetMapping("/{id}")
    public EmployeeResponse detail(@PathVariable Long id) {
        return employees.get(id);
    }

    @PatchMapping("/{id}")
    public EmployeeResponse patch(@PathVariable Long id, @Valid @RequestBody PatchRequest request) {
        return employees.patch(id, new EmployeeService.PatchCommand(
                request.version(), request.name(), request.phone(), request.note(), request.workTypes()));
    }

    @PostMapping("/{id}/leave")
    public EmployeeResponse leave(@PathVariable Long id, @Valid @RequestBody LeaveRequest request) {
        return employees.leave(id, new EmployeeService.LeaveCommand(request.reason(), request.date()));
    }

    @PostMapping("/{id}/rehire")
    public EmployeeResponse rehire(@PathVariable Long id, @Valid @RequestBody RehireRequest request) {
        return employees.rehire(id, new EmployeeService.RehireCommand(request.date()));
    }

    @GetMapping("/{id}/eligibility")
    public EligibilityResponse eligibility(@PathVariable Long id,
                                           @RequestParam(required = false) String workType) {
        var snapshot = eligibility.checkEligible(id, workType);
        return new EligibilityResponse(true, snapshot.employeeNo(), snapshot.name(), workType);
    }
}
