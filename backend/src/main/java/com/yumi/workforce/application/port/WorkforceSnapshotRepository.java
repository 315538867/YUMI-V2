package com.yumi.workforce.application.port;

import com.yumi.platform.api.value.ContentFingerprint;
import java.util.Optional;

/** 员工、小组与标准快照持久化端口。 */
public interface WorkforceSnapshotRepository {

    Optional<SubjectSnapshotRecord> findEmployeeSnapshot(
            String employeeBusinessId, long sourceContentVersion);

    Optional<SubjectSnapshotRecord> findTeamSnapshot(String teamBusinessId, long sourceContentVersion);

    Optional<SubjectSnapshotRecord> findSnapshotByBusinessId(String snapshotBusinessId);

    long maxSubjectSnapshotVersion(String subjectBusinessId);

    void insertEmployeeSnapshot(SubjectSnapshotRecord record);

    void insertTeamSnapshot(SubjectSnapshotRecord record);

    Optional<StandardSnapshotRecord> findStandardSnapshot(
            String standardType, String standardBusinessId, long sourceContentVersion);

    void insertStandardSnapshot(StandardSnapshotRecord record);

    /**
     * 执行主体快照记录。
     *
     * @param memberRefsJson 小组完整成员集合；员工快照为空
     */
    record SubjectSnapshotRecord(
            String snapshotBusinessId,
            String subjectType,
            String subjectBusinessId,
            long snapshotVersion,
            long sourceContentVersion,
            String payloadJson,
            String memberRefsJson,
            ContentFingerprint fingerprint,
            java.time.Instant createdAt) {
    }

    /** 标准快照记录。 */
    record StandardSnapshotRecord(
            String snapshotBusinessId,
            String standardType,
            String standardBusinessId,
            long sourceContentVersion,
            String payloadJson,
            ContentFingerprint fingerprint,
            java.time.Instant createdAt) {
    }
}
