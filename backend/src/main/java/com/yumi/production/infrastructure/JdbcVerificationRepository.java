package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.TaskStatus;
import com.yumi.production.application.port.VerificationRepository;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 核验事实持久化（MySQL）。 */
@Repository
public class JdbcVerificationRepository implements VerificationRepository {

    private final JdbcClient jdbc;

    public JdbcVerificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ItemVerificationRecord> findItemVerification(String taskItemBusinessId) {
        return jdbc.sql("""
                        SELECT task_item_business_id, task_business_id, assignment_version, planned_quantity,
                               unfinished_quantity, completed_quantity, qualified_quantity, rework_quantity,
                               scrapped_quantity, destroyed_quantity, scrap_offline_quantity, task_work_date,
                               verified_at, operator_id, result_note
                        FROM production_task_item_verifications
                        WHERE task_item_business_id = :id AND is_valid = 1
                        """)
                .param("id", taskItemBusinessId)
                .query((resultSet, rowNumber) -> new ItemVerificationRecord(
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("task_business_id"),
                        resultSet.getLong("assignment_version"),
                        resultSet.getInt("planned_quantity"),
                        resultSet.getInt("unfinished_quantity"),
                        resultSet.getInt("completed_quantity"),
                        resultSet.getInt("qualified_quantity"),
                        resultSet.getInt("rework_quantity"),
                        resultSet.getInt("scrapped_quantity"),
                        resultSet.getInt("destroyed_quantity"),
                        resultSet.getInt("scrap_offline_quantity"),
                        resultSet.getObject("task_work_date", LocalDate.class),
                        JdbcTimes.toInstant(resultSet.getObject("verified_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id"),
                        resultSet.getString("result_note")))
                .optional();
    }

    @Override
    public void insertItemVerification(ItemVerificationRecord record) {
        jdbc.sql("""
                        INSERT INTO production_task_item_verifications (
                            task_item_business_id, task_business_id, assignment_version, planned_quantity,
                            unfinished_quantity, completed_quantity, qualified_quantity, rework_quantity,
                            scrapped_quantity, destroyed_quantity, scrap_offline_quantity, task_work_date,
                            verified_at, operator_id, result_note, is_valid)
                        VALUES (:itemId, :taskId, :version, :planned, :unfinished, :completed, :qualified,
                                :rework, :scrapped, :destroyed, :offline, :workDate, :verifiedAt,
                                :operatorId, :note, 1)
                        """)
                .param("itemId", record.taskItemBusinessId())
                .param("taskId", record.taskBusinessId())
                .param("version", record.assignmentVersion())
                .param("planned", record.plannedQuantity())
                .param("unfinished", record.unfinishedQuantity())
                .param("completed", record.completedQuantity())
                .param("qualified", record.qualifiedQuantity())
                .param("rework", record.reworkQuantity())
                .param("scrapped", record.scrappedQuantity())
                .param("destroyed", record.destroyedQuantity())
                .param("offline", record.scrapOfflineQuantity())
                .param("workDate", Date.valueOf(record.taskWorkDate()))
                .param("verifiedAt", JdbcTimes.toUtc(record.verifiedAt()))
                .param("operatorId", record.operatorId())
                .param("note", record.resultNote())
                .update();
    }

    @Override
    public void updateItemVerification(ItemVerificationRecord record) {
        jdbc.sql("""
                        UPDATE production_task_item_verifications
                        SET assignment_version = :version, planned_quantity = :planned,
                            unfinished_quantity = :unfinished, completed_quantity = :completed,
                            qualified_quantity = :qualified, rework_quantity = :rework,
                            scrapped_quantity = :scrapped, destroyed_quantity = :destroyed,
                            scrap_offline_quantity = :offline, task_work_date = :workDate,
                            verified_at = :verifiedAt, operator_id = :operatorId, result_note = :note,
                            is_valid = 1
                        WHERE task_item_business_id = :itemId
                        """)
                .param("itemId", record.taskItemBusinessId())
                .param("version", record.assignmentVersion())
                .param("planned", record.plannedQuantity())
                .param("unfinished", record.unfinishedQuantity())
                .param("completed", record.completedQuantity())
                .param("qualified", record.qualifiedQuantity())
                .param("rework", record.reworkQuantity())
                .param("scrapped", record.scrappedQuantity())
                .param("destroyed", record.destroyedQuantity())
                .param("offline", record.scrapOfflineQuantity())
                .param("workDate", java.sql.Date.valueOf(record.taskWorkDate()))
                .param("verifiedAt", JdbcTimes.toUtc(record.verifiedAt()))
                .param("operatorId", record.operatorId())
                .param("note", record.resultNote())
                .update();
    }

    @Override
    public void insertExecution(ExecutionFact fact) {
        jdbc.sql("""
                        INSERT INTO production_execution_facts (
                            fact_business_id, task_business_id, task_item_business_id, demand_business_id,
                            demand_type, production_source_business_id, physical_root_source_business_id,
                            product_business_id, product_snapshot_ref, scheme_business_id,
                            scheme_snapshot_ref, process_code, subject_type, subject_business_id, quantity,
                            task_work_date, occurred_at, is_valid)
                        VALUES (:factId, :taskId, :itemId, :demandId, :demandType, :sourceId, :physicalRoot,
                                :productId, :productSnapshotRef, :schemeId, :schemeSnapshotRef, :processCode,
                                :subjectType, :subjectId, :quantity, :workDate, :occurredAt, 1)
                        """)
                .param("factId", fact.factBusinessId())
                .param("taskId", fact.taskBusinessId())
                .param("itemId", fact.taskItemBusinessId())
                .param("demandId", fact.demandBusinessId())
                .param("demandType", fact.demandType().name())
                .param("sourceId", fact.productionSourceBusinessId())
                .param("physicalRoot", fact.physicalRootSourceBusinessId())
                .param("productId", fact.productBusinessId())
                .param("productSnapshotRef", fact.productSnapshotRef())
                .param("schemeId", fact.schemeBusinessId())
                .param("schemeSnapshotRef", fact.schemeSnapshotRef())
                .param("processCode", fact.processCode())
                .param("subjectType", fact.subjectType())
                .param("subjectId", fact.subjectBusinessId())
                .param("quantity", fact.quantity())
                .param("workDate", Date.valueOf(fact.taskWorkDate()))
                .param("occurredAt", JdbcTimes.toUtc(fact.occurredAt()))
                .update();
    }

    @Override
    public void insertQuality(QualityFact fact) {
        jdbc.sql("""
                        INSERT INTO production_quality_facts (
                            fact_business_id, task_item_business_id, quality_code, quantity, occurred_at, is_valid)
                        VALUES (:factId, :itemId, :qualityCode, :quantity, :occurredAt, 1)
                        """)
                .param("factId", fact.factBusinessId())
                .param("itemId", fact.taskItemBusinessId())
                .param("qualityCode", fact.qualityCode())
                .param("quantity", fact.quantity())
                .param("occurredAt", JdbcTimes.toUtc(fact.occurredAt()))
                .update();
    }

    @Override
    public void insertDefect(DefectFact fact) {
        jdbc.sql("""
                        INSERT INTO production_defect_facts (
                            fact_business_id, task_item_business_id, defect_reason_code, defect_reason_name,
                            defect_reason_text, quantity, occurred_at, is_valid)
                        VALUES (:factId, :itemId, :reasonCode, :reasonName, :reasonText, :quantity, :occurredAt, 1)
                        """)
                .param("factId", fact.factBusinessId())
                .param("itemId", fact.taskItemBusinessId())
                .param("reasonCode", fact.defectReasonCode())
                .param("reasonName", fact.defectReasonName())
                .param("reasonText", fact.defectReasonText())
                .param("quantity", fact.quantity())
                .param("occurredAt", JdbcTimes.toUtc(fact.occurredAt()))
                .update();
    }

    @Override
    public void insertDisposition(DispositionFact fact) {
        jdbc.sql("""
                        INSERT INTO production_disposition_facts (
                            fact_business_id, defect_fact_business_id, task_item_business_id,
                            disposition_type, physical_destination, quantity, terminal_exit_business_id,
                            repair_source_business_id, occurred_at, is_valid)
                        VALUES (:factId, :defectId, :itemId, :type, :destination, :quantity, :exitId,
                                :repairSourceId, :occurredAt, 1)
                        """)
                .param("factId", fact.factBusinessId())
                .param("defectId", fact.defectFactBusinessId())
                .param("itemId", fact.taskItemBusinessId())
                .param("type", fact.dispositionType())
                .param("destination", fact.physicalDestination().name())
                .param("quantity", fact.quantity())
                .param("exitId", fact.terminalExitBusinessId())
                .param("repairSourceId", fact.repairSourceBusinessId())
                .param("occurredAt", JdbcTimes.toUtc(fact.occurredAt()))
                .update();
    }

    @Override
    public void insertTerminalExit(TerminalExit exit) {
        jdbc.sql("""
                        INSERT INTO production_terminal_exits (
                            terminal_exit_business_id, production_source_business_id,
                            physical_root_source_business_id, demand_business_id, task_item_business_id,
                            exit_reason, physical_destination, quantity, occurred_at, is_valid)
                        VALUES (:exitId, :sourceId, :physicalRoot, :demandId, :itemId, :reason,
                                :destination, :quantity, :occurredAt, 1)
                        """)
                .param("exitId", exit.terminalExitBusinessId())
                .param("sourceId", exit.productionSourceBusinessId())
                .param("physicalRoot", exit.physicalRootSourceBusinessId())
                .param("demandId", exit.demandBusinessId())
                .param("itemId", exit.taskItemBusinessId())
                .param("reason", exit.exitReason())
                .param("destination", exit.physicalDestination().name())
                .param("quantity", exit.quantity())
                .param("occurredAt", JdbcTimes.toUtc(exit.occurredAt()))
                .update();
    }

    @Override
    public void insertOfflineTransfer(OfflineTransferRecord record) {
        jdbc.sql("""
                        INSERT INTO production_offline_inventory_transfers (
                            transfer_fact_business_id, terminal_exit_business_id,
                            production_source_business_id, physical_root_source_business_id,
                            demand_business_id, product_business_id, reason_code,
                            upstream_change_business_id, offline_inventory_name, offline_inventory_note,
                            handed_over_by, received_by, transferred_at, quantity, occurred_at,
                            operator_id, is_valid)
                        VALUES (:id, :exitId, :sourceId, :physicalRoot, :demandId, :productId,
                                :reasonCode, NULL, :inventoryName, :inventoryNote, :handedOverBy,
                                :receivedBy, :transferredAt, :quantity, :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.transferFactBusinessId())
                .param("exitId", record.terminalExitBusinessId())
                .param("sourceId", record.productionSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("demandId", record.demandBusinessId())
                .param("productId", record.productBusinessId())
                .param("reasonCode", record.reasonCode())
                .param("inventoryName", record.offlineInventoryName())
                .param("inventoryNote", record.offlineInventoryNote())
                .param("handedOverBy", record.handedOverBy())
                .param("receivedBy", record.receivedBy())
                .param("transferredAt", JdbcTimes.toUtc(record.transferredAt()))
                .param("quantity", record.quantity())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertResponsibility(ResponsibilityFact fact) {
        jdbc.sql("""
                        INSERT INTO production_responsibility_facts (
                            responsibility_chain_business_id, disposition_fact_business_id,
                            terminal_exit_business_id, responsibility_type, responsible_subject_business_id,
                            waived_reason, quantity, business_date, occurred_at, is_valid)
                        VALUES (:chainId, :dispositionId, :exitId, :type, :subjectId, :waivedReason,
                                :quantity, :businessDate, :occurredAt, 1)
                        """)
                .param("chainId", fact.responsibilityChainBusinessId())
                .param("dispositionId", fact.dispositionFactBusinessId())
                .param("exitId", fact.terminalExitBusinessId())
                .param("type", fact.responsibilityType())
                .param("subjectId", fact.responsibleSubjectBusinessId())
                .param("waivedReason", fact.waivedReason())
                .param("quantity", fact.quantity())
                .param("businessDate", Date.valueOf(fact.businessDate()))
                .param("occurredAt", JdbcTimes.toUtc(fact.occurredAt()))
                .update();
    }

    @Override
    public void insertResponsibilityRevision(
            String revisionBusinessId, String responsibilityChainBusinessId,
            String dispositionFactBusinessId, String responsibilityType,
            String responsibleSubjectBusinessId, String waivedReason, int quantity,
            java.time.LocalDate businessDate, String reasonText,
            java.time.Instant occurredAt, String operatorId) {
        jdbc.sql("""
                        INSERT INTO production_responsibility_revisions (
                            revision_business_id, responsibility_chain_business_id,
                            disposition_fact_business_id, effect_type, responsibility_type,
                            responsible_subject_business_id, waived_reason, quantity,
                            business_date, reason_text, occurred_at, operator_id)
                        VALUES (:revisionId, :chainId, :dispositionId, 'APPLY', :type,
                                :subjectId, :waivedReason, :quantity, :businessDate,
                                :reasonText, :occurredAt, :operatorId)
                        """)
                .param("revisionId", revisionBusinessId)
                .param("chainId", responsibilityChainBusinessId)
                .param("dispositionId", dispositionFactBusinessId)
                .param("type", responsibilityType)
                .param("subjectId", responsibleSubjectBusinessId)
                .param("waivedReason", waivedReason)
                .param("quantity", quantity)
                .param("businessDate", java.sql.Date.valueOf(businessDate))
                .param("reasonText", reasonText)
                .param("occurredAt", com.yumi.platform.api.jdbc.JdbcTimes.toUtc(occurredAt))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public void insertOtherTaskVerification(OtherTaskVerification fact) {
        jdbc.sql("""
                        INSERT INTO production_other_task_verification_facts (
                            fact_business_id, task_business_id, assignment_version, assessment_business_id,
                            planned_quantity, completed_quantity, unfinished_quantity, result_note,
                            verified_at, task_work_date, operator_id, is_valid)
                        VALUES (:factId, :taskId, :version, :assessmentId, :planned, :completed, :unfinished,
                                :note, :verifiedAt, :workDate, :operatorId, 1)
                        """)
                .param("factId", fact.factBusinessId())
                .param("taskId", fact.taskBusinessId())
                .param("version", fact.assignmentVersion())
                .param("assessmentId", fact.assessmentBusinessId())
                .param("planned", fact.plannedQuantity())
                .param("completed", fact.completedQuantity())
                .param("unfinished", fact.unfinishedQuantity())
                .param("note", fact.resultNote())
                .param("verifiedAt", JdbcTimes.toUtc(fact.verifiedAt()))
                .param("workDate", Date.valueOf(fact.taskWorkDate()))
                .param("operatorId", fact.operatorId())
                .update();
    }

    @Override
    public Optional<OtherTaskVerification> findOtherTaskVerification(String taskBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, task_business_id, assignment_version, assessment_business_id,
                               planned_quantity, completed_quantity, unfinished_quantity, result_note,
                               verified_at, task_work_date, operator_id
                        FROM production_other_task_verification_facts
                        WHERE task_business_id = :taskId AND is_valid = 1
                        """)
                .param("taskId", taskBusinessId)
                .query((resultSet, rowNumber) -> new OtherTaskVerification(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("task_business_id"),
                        resultSet.getLong("assignment_version"),
                        resultSet.getString("assessment_business_id"),
                        resultSet.getObject("planned_quantity") == null
                                ? null : resultSet.getInt("planned_quantity"),
                        resultSet.getObject("completed_quantity") == null
                                ? null : resultSet.getInt("completed_quantity"),
                        resultSet.getObject("unfinished_quantity") == null
                                ? null : resultSet.getInt("unfinished_quantity"),
                        resultSet.getString("result_note"),
                        JdbcTimes.toInstant(resultSet.getObject("verified_at", java.time.LocalDateTime.class)),
                        resultSet.getObject("task_work_date", LocalDate.class),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public void insertLaborSource(LaborSource source) {
        jdbc.sql("""
                        INSERT INTO production_labor_sources (
                            labor_source_business_id, task_business_id, task_item_business_id,
                            demand_business_id, production_source_business_id, subject_type,
                            subject_business_id, task_type, process_code, quantity, unit_labor_amount,
                            business_date, occurred_at, effective_status)
                        VALUES (:id, :taskId, :itemId, :demandId, :sourceId, :subjectType, :subjectId,
                                :taskType, :processCode, :quantity, :unitAmount, :businessDate, :occurredAt,
                                :status)
                        """)
                .param("id", source.laborSourceBusinessId())
                .param("taskId", source.taskBusinessId())
                .param("itemId", source.taskItemBusinessId())
                .param("demandId", source.demandBusinessId())
                .param("sourceId", source.productionSourceBusinessId())
                .param("subjectType", source.subjectType())
                .param("subjectId", source.subjectBusinessId())
                .param("taskType", source.taskType())
                .param("processCode", source.processCode())
                .param("quantity", source.quantity())
                .param("unitAmount", source.unitLaborAmount())
                .param("businessDate", Date.valueOf(source.businessDate()))
                .param("occurredAt", JdbcTimes.toUtc(source.occurredAt()))
                .param("status", source.effectiveStatus())
                .update();
    }

    @Override
    public void insertMaterialCostSource(MaterialCostSource source) {
        jdbc.sql("""
                        INSERT INTO production_material_cost_sources (
                            material_source_business_id, task_business_id, task_item_business_id,
                            demand_business_id, production_source_business_id,
                            physical_root_source_business_id, product_business_id, product_snapshot_ref,
                            scheme_business_id, scheme_snapshot_ref, component_code, process_code,
                            quantity, business_date, occurred_at, effective_status)
                        VALUES (:id, :taskId, :itemId, :demandId, :sourceId, :physicalRoot, :productId,
                                :productSnapshotRef, :schemeId, :schemeSnapshotRef, :componentCode,
                                :processCode, :quantity, :businessDate, :occurredAt, :status)
                        """)
                .param("id", source.materialSourceBusinessId())
                .param("taskId", source.taskBusinessId())
                .param("itemId", source.taskItemBusinessId())
                .param("demandId", source.demandBusinessId())
                .param("sourceId", source.productionSourceBusinessId())
                .param("physicalRoot", source.physicalRootSourceBusinessId())
                .param("productId", source.productBusinessId())
                .param("productSnapshotRef", source.productSnapshotRef())
                .param("schemeId", source.schemeBusinessId())
                .param("schemeSnapshotRef", source.schemeSnapshotRef())
                .param("componentCode", source.componentCode())
                .param("processCode", source.processCode())
                .param("quantity", source.quantity())
                .param("businessDate", Date.valueOf(source.businessDate()))
                .param("occurredAt", JdbcTimes.toUtc(source.occurredAt()))
                .param("status", source.effectiveStatus())
                .update();
    }

    @Override
    public void updateTaskStatus(String taskBusinessId, TaskStatus status, long rowVersion) {
        jdbc.sql("""
                        UPDATE production_tasks SET status = :status, row_version = row_version + 1,
                            updated_at = UTC_TIMESTAMP(6)
                        WHERE task_business_id = :taskId
                        """)
                .param("status", status.name())
                .param("taskId", taskBusinessId)
                .update();
    }

    @Override
    public void updateTaskItemVerification(
            String taskItemBusinessId, int completed, int unfinished, int qualified, int rework, int scrapped,
            int destroyed, int scrapOffline, String verificationStatus) {
        jdbc.sql("""
                        UPDATE production_task_items
                        SET verification_status = :status, completed_quantity = :completed,
                            unfinished_quantity = :unfinished, qualified_quantity = :qualified,
                            rework_quantity = :rework, scrapped_quantity = :scrapped,
                            destroyed_quantity = :destroyed, scrap_offline_quantity = :offline,
                            row_version = row_version + 1
                        WHERE task_item_business_id = :itemId
                        """)
                .param("status", verificationStatus)
                .param("completed", completed)
                .param("unfinished", unfinished)
                .param("qualified", qualified)
                .param("rework", rework)
                .param("scrapped", scrapped)
                .param("destroyed", destroyed)
                .param("offline", scrapOffline)
                .param("itemId", taskItemBusinessId)
                .update();
    }

    @Override
    public void updateDemandProjection(
            String demandBusinessId, int pending, int inProcess, int targetReady, int gap) {
        jdbc.sql("""
                        UPDATE production_demand_quantity_projections
                        SET pending_work_quantity = :pending, in_process_quantity = :inProcess,
                            target_ready_quantity = :targetReady, fulfillment_gap_quantity = :gap,
                            updated_at = UTC_TIMESTAMP(6), row_version = row_version + 1
                        WHERE demand_business_id = :id
                        """)
                .param("pending", pending)
                .param("inProcess", inProcess)
                .param("targetReady", targetReady)
                .param("gap", gap)
                .param("id", demandBusinessId)
                .update();
    }

    @Override
    public void updateStageProjection(
            String productionSourceBusinessId, String stageCode, int availableDelta, int reservedDelta) {
        if (availableDelta == 0 && reservedDelta == 0) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO production_stage_quantity_projections (
                            production_source_business_id, physical_root_source_business_id, stage_code,
                            quality_code, disposition_code, physical_destination, available_quantity,
                            reserved_quantity, updated_at)
                        SELECT production_source_business_id, physical_root_source_business_id, :stageCode,
                               'QUALIFIED', '', 'IN_SYSTEM', :availableDelta, :reservedDelta, UTC_TIMESTAMP(6)
                        FROM production_sources WHERE production_source_business_id = :sourceId
                        ON DUPLICATE KEY UPDATE
                            available_quantity = available_quantity + :availableDelta,
                            reserved_quantity = reserved_quantity + :reservedDelta,
                            updated_at = UTC_TIMESTAMP(6)
                        """)
                .param("stageCode", stageCode)
                .param("availableDelta", availableDelta)
                .param("reservedDelta", reservedDelta)
                .param("sourceId", productionSourceBusinessId)
                .update();
    }

    @Override
    public void invalidateFactsOfItem(String taskItemBusinessId) {
        for (String statement : new String[] {
                "UPDATE production_execution_facts SET is_valid = 0 WHERE task_item_business_id = :id",
                "UPDATE production_quality_facts SET is_valid = 0 WHERE task_item_business_id = :id",
                "UPDATE production_defect_facts SET is_valid = 0 WHERE task_item_business_id = :id",
                "UPDATE production_disposition_facts SET is_valid = 0 WHERE task_item_business_id = :id",
                "UPDATE production_terminal_exits SET is_valid = 0 WHERE task_item_business_id = :id",
                "UPDATE production_responsibility_facts SET is_valid = 0, row_version = row_version + 1"
                        + " WHERE disposition_fact_business_id IN (SELECT fact_business_id"
                        + " FROM production_disposition_facts WHERE task_item_business_id = :id)",
                "UPDATE production_material_cost_sources SET effective_status = 'REVERSE'"
                        + " WHERE task_item_business_id = :id AND effective_status = 'APPLY'",
                "UPDATE production_repair_sources SET status = 'CORRECTED'"
                        + " WHERE original_defect_fact_business_id IN (SELECT fact_business_id"
                        + " FROM production_defect_facts WHERE task_item_business_id = :id)",
                "UPDATE production_task_item_verifications SET is_valid = 0"
                        + " WHERE task_item_business_id = :id"}) {
            jdbc.sql(statement).param("id", taskItemBusinessId).update();
        }
    }

    @Override
    public void invalidateLaborSourcesOfTask(String taskBusinessId) {
        jdbc.sql("""
                        UPDATE production_labor_sources SET effective_status = 'REVERSE'
                        WHERE task_business_id = :taskId AND effective_status = 'APPLY'
                        """)
                .param("taskId", taskBusinessId)
                .update();
    }

    @Override
    public int sumLaborQuantityOfTask(String taskBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM production_labor_sources
                        WHERE task_business_id = :taskId AND effective_status = 'APPLY'
                        """)
                .param("taskId", taskBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public int sumMaterialQuantityOfItem(String taskItemBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM production_material_cost_sources
                        WHERE task_item_business_id = :id AND effective_status = 'APPLY'
                        """)
                .param("id", taskItemBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public int sumResponsibilityQuantityOfItem(String taskItemBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(quantity), 0) FROM production_responsibility_facts
                        WHERE is_valid = 1 AND disposition_fact_business_id IN (
                            SELECT fact_business_id FROM production_disposition_facts
                            WHERE task_item_business_id = :id AND disposition_type = 'SCRAPPED')
                        """)
                .param("id", taskItemBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void insertRepairSource(RepairSourceRecord record) {
        jdbc.sql("""
                        INSERT INTO production_repair_sources (
                            repair_source_business_id, origin_type, origin_module, origin_type_code,
                            origin_business_id, demand_business_id, production_source_business_id,
                            physical_root_source_business_id, original_defect_fact_business_id,
                            repair_process_code, original_quantity, available_quantity, reserved_quantity,
                            completed_quantity, status, row_version, created_at)
                        VALUES (:id, :originType, :originModule, :originTypeCode, :originId, :demandId,
                                :sourceId, :physicalRoot, :defectId, :repairProcess, :quantity,
                                :quantity, 0, 0, 'ACTIVE', 1, UTC_TIMESTAMP(6))
                        """)
                .param("id", record.repairSourceBusinessId())
                .param("originType", record.originType())
                .param("originModule", record.originModule())
                .param("originTypeCode", record.originTypeCode())
                .param("originId", record.originBusinessId())
                .param("demandId", record.demandBusinessId())
                .param("sourceId", record.productionSourceBusinessId())
                .param("physicalRoot", record.physicalRootSourceBusinessId())
                .param("defectId", record.originalDefectFactBusinessId())
                .param("repairProcess", record.repairProcessCode())
                .param("quantity", record.originalQuantity())
                .update();
    }

    @Override
    public void insertRepairSourceFact(
            String repairSourceBusinessId, String operationType, int quantity,
            String taskBusinessId, String taskItemBusinessId, Instant occurredAt, String operatorId) {
        jdbc.sql("""
                        INSERT INTO production_repair_source_facts (
                            fact_business_id, repair_source_business_id, operation_type, quantity,
                            task_business_id, task_item_business_id, occurred_at, operator_id, is_valid)
                        VALUES (:factId, :sourceId, :operationType, :quantity, :taskId, :itemId,
                                :occurredAt, :operatorId, 1)
                        """)
                .param("factId", "RSF-" + java.util.UUID.randomUUID())
                .param("sourceId", repairSourceBusinessId)
                .param("operationType", operationType)
                .param("quantity", quantity)
                .param("taskId", taskBusinessId)
                .param("itemId", taskItemBusinessId)
                .param("occurredAt", JdbcTimes.toUtc(occurredAt))
                .param("operatorId", operatorId)
                .update();
    }

    @Override
    public Optional<DefectReasonRecord> findDefectReason(String code) {
        return jdbc.sql("""
                        SELECT defect_reason_code, name, status, is_other
                        FROM production_defect_reasons WHERE defect_reason_code = :code
                        """)
                .param("code", code)
                .query((resultSet, rowNumber) -> new DefectReasonRecord(
                        resultSet.getString("defect_reason_code"),
                        resultSet.getString("name"),
                        resultSet.getString("status"),
                        resultSet.getBoolean("is_other")))
                .optional();
    }
}
