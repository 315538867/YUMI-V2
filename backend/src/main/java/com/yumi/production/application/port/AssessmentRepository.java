package com.yumi.production.application.port;

import java.util.Optional;

/** 工作量评估持久化端口。 */
public interface AssessmentRepository {

    void insertAssessment(AssessmentRecord record);

    void insertAssessmentItem(AssessmentItemRecord record);

    void insertMemberLoad(MemberLoadRecord record);

    Optional<AssessmentRecord> findByTask(String taskBusinessId, long assignmentVersion);

    /** 评估记录。 */
    record AssessmentRecord(
            String assessmentBusinessId,
            String taskBusinessId,
            long assignmentVersion,
            long taskStandardSeconds,
            long availableEffectiveSeconds,
            String assessmentResult,
            String subjectSnapshotRef,
            String workTimeStandardSnapshotRef,
            String efficiencySnapshotRef,
            String formulaRuleVersion,
            String inputFingerprint,
            String overrideReason) {
    }

    /** 评估明细。 */
    record AssessmentItemRecord(
            String assessmentBusinessId,
            String taskItemBusinessId,
            String productSnapshotRef,
            String schemeSnapshotRef,
            String processCode,
            int quantity,
            int itemStandardMinutes,
            long itemBaseSeconds,
            String itemInputFingerprint) {
    }

    /** 评估成员负载。 */
    record MemberLoadRecord(
            String assessmentBusinessId,
            String employeeBusinessId,
            String employeeSnapshotRef,
            long occupiedSecondsBefore,
            long availableSecondsBefore,
            long occupiedSecondsAfter) {
    }
}
