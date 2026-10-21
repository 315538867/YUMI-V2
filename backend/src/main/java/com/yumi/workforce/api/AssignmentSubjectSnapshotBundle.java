package com.yumi.workforce.api;

import java.util.List;

/**
 * 一次取得的执行主体与标准快照集合。
 *
 * <p>任务安排必须同时持有主体快照、效率标准快照与每日时间标准快照，保证同一依据。
 */
public record AssignmentSubjectSnapshotBundle(
        AssignmentSubjectSnapshotRef subjectSnapshot,
        AssignmentSubjectSnapshotView subjectView,
        StandardSnapshotRef efficiencyStandardSnapshot,
        StandardSnapshotRef dailyWorkTimeSnapshot,
        int dailyEffectiveMinutes,
        List<EmployeeView.QualificationView> qualifications) {

    public AssignmentSubjectSnapshotBundle {
        qualifications = List.copyOf(qualifications);
    }

    /** 标准快照引用：类型 + 稳定身份 + 内容版本共同去重。 */
    public record StandardSnapshotRef(
            String standardType,
            String standardBusinessId,
            String snapshotBusinessId,
            long sourceContentVersion,
            com.yumi.platform.api.value.ContentFingerprint fingerprint) {
    }
}
