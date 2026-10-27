package com.yumi.workforce.api;

import java.time.Instant;
import java.util.List;

/** 小组历史：成员变化与状态记录，按时间倒序。 */
public record TeamHistoryView(
        List<MemberFactView> members,
        List<StatusFactView> statuses) {

    public TeamHistoryView {
        members = List.copyOf(members);
        statuses = List.copyOf(statuses);
    }

    public record MemberFactView(
            String factBusinessId, String employeeBusinessId, String employeeNo, String employeeName,
            String factType, Instant occurredAt, String operatorId) {
    }

    public record StatusFactView(
            String factBusinessId, String factType, TeamStatus status, String reason,
            Instant occurredAt, String operatorId) {
    }
}
