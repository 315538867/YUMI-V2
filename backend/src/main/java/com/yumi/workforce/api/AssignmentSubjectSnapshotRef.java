package com.yumi.workforce.api;

import com.yumi.platform.api.value.ContentFingerprint;

/**
 * 执行主体快照引用。
 *
 * <p>{@code snapshotType} 区分员工快照与小组快照；两者都可作为任务主体依据。
 */
public record AssignmentSubjectSnapshotRef(
        AssignmentSubjectType subjectType,
        String snapshotBusinessId,
        String subjectBusinessId,
        long snapshotVersion,
        long sourceContentVersion,
        ContentFingerprint fingerprint) {
}
