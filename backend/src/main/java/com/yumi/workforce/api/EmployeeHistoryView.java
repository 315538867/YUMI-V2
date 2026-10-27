package com.yumi.workforce.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 员工历史：就业事实与资格变更，按时间倒序。 */
public record EmployeeHistoryView(
        List<EmploymentFactView> employment,
        List<QualificationFactView> qualifications) {

    public EmployeeHistoryView {
        employment = List.copyOf(employment);
        qualifications = List.copyOf(qualifications);
    }

    public record EmploymentFactView(
            String factBusinessId, String factType, LocalDate effectiveDate, String reason,
            Instant occurredAt, String operatorId) {
    }

    public record QualificationFactView(
            String factBusinessId, String workTypeCode, String factType,
            LocalDate effectiveDate, Instant occurredAt, String operatorId) {
    }
}
