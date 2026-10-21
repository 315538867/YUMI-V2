package com.yumi.workforce.api;

import java.util.List;

/** 执行主体快照解析结果：冻结的员工身份或小组完整成员集合。 */
public record AssignmentSubjectSnapshotView(
        AssignmentSubjectSnapshotRef ref,
        String name,
        String employeeNo,
        String teamNo,
        List<MemberSnapshot> members,
        java.math.BigDecimal efficiencyFactor,
        String workTypeCode) {

    public AssignmentSubjectSnapshotView {
        members = List.copyOf(members);
    }

    /** 小组成员快照。 */
    public record MemberSnapshot(String employeeBusinessId, String employeeNo, String name) {
    }
}
