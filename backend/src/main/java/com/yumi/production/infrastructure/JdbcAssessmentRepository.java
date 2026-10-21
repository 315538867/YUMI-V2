package com.yumi.production.infrastructure;

import com.yumi.production.application.port.AssessmentRepository;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 工作量评估持久化（MySQL）。 */
@Repository
public class JdbcAssessmentRepository implements AssessmentRepository {

    private final JdbcClient jdbc;

    public JdbcAssessmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertAssessment(AssessmentRecord record) {
        jdbc.sql("""
                        INSERT INTO production_workload_assessments (
                            assessment_business_id, task_business_id, assignment_version,
                            task_standard_seconds, available_effective_seconds, assessment_result,
                            subject_snapshot_ref, work_time_standard_snapshot_ref,
                            efficiency_snapshot_ref, formula_rule_version, input_fingerprint,
                            override_reason, created_at)
                        VALUES (:assessmentId, :taskId, :version, :standardSeconds, :availableSeconds,
                                :result, :subjectSnapshotRef, :workTimeSnapshotRef, :efficiencySnapshotRef,
                                :ruleVersion, :fingerprint, :overrideReason, UTC_TIMESTAMP(6))
                        """)
                .param("assessmentId", record.assessmentBusinessId())
                .param("taskId", record.taskBusinessId())
                .param("version", record.assignmentVersion())
                .param("standardSeconds", record.taskStandardSeconds())
                .param("availableSeconds", record.availableEffectiveSeconds())
                .param("result", record.assessmentResult())
                .param("subjectSnapshotRef", record.subjectSnapshotRef())
                .param("workTimeSnapshotRef", record.workTimeStandardSnapshotRef())
                .param("efficiencySnapshotRef", record.efficiencySnapshotRef())
                .param("ruleVersion", record.formulaRuleVersion())
                .param("fingerprint", record.inputFingerprint())
                .param("overrideReason", record.overrideReason())
                .update();
    }

    @Override
    public void insertAssessmentItem(AssessmentItemRecord record) {
        jdbc.sql("""
                        INSERT INTO production_workload_assessment_items (
                            assessment_business_id, task_item_business_id, product_snapshot_ref,
                            scheme_snapshot_ref, process_code, quantity, item_standard_minutes,
                            item_base_seconds, item_input_fingerprint)
                        VALUES (:assessmentId, :itemId, :productSnapshotRef, :schemeSnapshotRef,
                                :processCode, :quantity, :standardMinutes, :baseSeconds, :fingerprint)
                        """)
                .param("assessmentId", record.assessmentBusinessId())
                .param("itemId", record.taskItemBusinessId())
                .param("productSnapshotRef", record.productSnapshotRef())
                .param("schemeSnapshotRef", record.schemeSnapshotRef())
                .param("processCode", record.processCode())
                .param("quantity", record.quantity())
                .param("standardMinutes", record.itemStandardMinutes())
                .param("baseSeconds", record.itemBaseSeconds())
                .param("fingerprint", record.itemInputFingerprint())
                .update();
    }

    @Override
    public void insertMemberLoad(MemberLoadRecord record) {
        jdbc.sql("""
                        INSERT INTO production_assessment_member_loads (
                            assessment_business_id, employee_business_id, employee_snapshot_ref,
                            occupied_seconds_before, available_seconds_before, occupied_seconds_after)
                        VALUES (:assessmentId, :employeeId, :employeeSnapshotRef, :occupiedBefore,
                                :availableBefore, :occupiedAfter)
                        """)
                .param("assessmentId", record.assessmentBusinessId())
                .param("employeeId", record.employeeBusinessId())
                .param("employeeSnapshotRef", record.employeeSnapshotRef())
                .param("occupiedBefore", record.occupiedSecondsBefore())
                .param("availableBefore", record.availableSecondsBefore())
                .param("occupiedAfter", record.occupiedSecondsAfter())
                .update();
    }

    @Override
    public Optional<AssessmentRecord> findByTask(String taskBusinessId, long assignmentVersion) {
        return jdbc.sql("""
                        SELECT assessment_business_id, task_business_id, assignment_version,
                               task_standard_seconds, available_effective_seconds, assessment_result,
                               subject_snapshot_ref, work_time_standard_snapshot_ref,
                               efficiency_snapshot_ref, formula_rule_version, input_fingerprint,
                               override_reason
                        FROM production_workload_assessments
                        WHERE task_business_id = :taskId AND assignment_version = :version
                        """)
                .param("taskId", taskBusinessId)
                .param("version", assignmentVersion)
                .query((resultSet, rowNumber) -> new AssessmentRecord(
                        resultSet.getString("assessment_business_id"),
                        resultSet.getString("task_business_id"),
                        resultSet.getLong("assignment_version"),
                        resultSet.getLong("task_standard_seconds"),
                        resultSet.getLong("available_effective_seconds"),
                        resultSet.getString("assessment_result"),
                        resultSet.getString("subject_snapshot_ref"),
                        resultSet.getString("work_time_standard_snapshot_ref"),
                        resultSet.getString("efficiency_snapshot_ref"),
                        resultSet.getString("formula_rule_version"),
                        resultSet.getString("input_fingerprint"),
                        resultSet.getString("override_reason")))
                .optional();
    }
}
