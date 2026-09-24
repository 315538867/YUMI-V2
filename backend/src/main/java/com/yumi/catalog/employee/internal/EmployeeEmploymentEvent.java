package com.yumi.catalog.employee.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 雇佣事件（V4 employee_employment_events）：只追加，记录 HIRE/LEAVE/REHIRE 及原因。
 */
@Entity
@Table(name = "employee_employment_events")
public class EmployeeEmploymentEvent {

    public static final String TYPE_HIRE = "HIRE";
    public static final String TYPE_LEAVE = "LEAVE";
    public static final String TYPE_REHIRE = "REHIRE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "event_type", nullable = false, length = 16)
    private String eventType;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "request_id", length = 64)
    private String requestId;

    protected EmployeeEmploymentEvent() {
    }

    public EmployeeEmploymentEvent(Long employeeId, String eventType, LocalDate eventDate,
                                   String reason, String requestId) {
        this.employeeId = employeeId;
        this.eventType = eventType;
        this.eventDate = eventDate;
        this.reason = reason;
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

    public String getEventType() {
        return eventType;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public String getReason() {
        return reason;
    }
}
