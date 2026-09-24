package com.yumi.catalog.employee.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 员工工种（V4 employee_work_types，任务 2.26 改为按工种 id 引用）：只插删不更新，编辑时整体替换。
 */
@Entity
@Table(name = "employee_work_types", uniqueConstraints = @UniqueConstraint(
        name = "uk_employee_work_types_employee_type", columnNames = {"employee_id", "work_type_id"}))
public class EmployeeWorkType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "work_type_id", nullable = false)
    private Long workTypeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "request_id", length = 64)
    private String requestId;

    protected EmployeeWorkType() {
    }

    public EmployeeWorkType(Long employeeId, Long workTypeId, String requestId) {
        this.employeeId = employeeId;
        this.workTypeId = workTypeId;
        this.requestId = requestId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getWorkTypeId() {
        return workTypeId;
    }
}
