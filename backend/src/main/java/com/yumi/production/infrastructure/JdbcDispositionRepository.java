package com.yumi.production.infrastructure;

import com.yumi.production.api.DefectDispositionType;
import com.yumi.production.api.DefectReasonStatus;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.application.port.DispositionRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 处置、责任与缺陷原因持久化（MySQL）。 */
@Repository
public class JdbcDispositionRepository implements DispositionRepository {

    private final JdbcClient jdbc;

    public JdbcDispositionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DefectRow> findDefects(String taskItemBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, task_item_business_id, defect_reason_code,
                               defect_reason_name, quantity
                        FROM production_defect_facts
                        WHERE task_item_business_id = :itemId AND is_valid = 1
                        ORDER BY fact_business_id
                        """)
                .param("itemId", taskItemBusinessId)
                .query((resultSet, rowNumber) -> new DefectRow(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("defect_reason_code"),
                        resultSet.getString("defect_reason_name"),
                        resultSet.getInt("quantity")))
                .list();
    }

    @Override
    public List<DispositionRow> findDispositions(String defectFactBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, defect_fact_business_id, disposition_type,
                               physical_destination, quantity, terminal_exit_business_id,
                               repair_source_business_id
                        FROM production_disposition_facts
                        WHERE defect_fact_business_id = :defectId AND is_valid = 1
                        ORDER BY fact_business_id
                        """)
                .param("defectId", defectFactBusinessId)
                .query((resultSet, rowNumber) -> new DispositionRow(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("defect_fact_business_id"),
                        DefectDispositionType.valueOf(resultSet.getString("disposition_type")),
                        PhysicalDestination.valueOf(resultSet.getString("physical_destination")),
                        resultSet.getInt("quantity"),
                        resultSet.getString("terminal_exit_business_id"),
                        resultSet.getString("repair_source_business_id")))
                .list();
    }

    @Override
    public List<DispositionRow> findDispositionsOf(String dispositionFactBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, defect_fact_business_id, disposition_type,
                               physical_destination, quantity, terminal_exit_business_id,
                               repair_source_business_id
                        FROM production_disposition_facts
                        WHERE fact_business_id = :id AND is_valid = 1
                        """)
                .param("id", dispositionFactBusinessId)
                .query((resultSet, rowNumber) -> new DispositionRow(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("defect_fact_business_id"),
                        DefectDispositionType.valueOf(resultSet.getString("disposition_type")),
                        PhysicalDestination.valueOf(resultSet.getString("physical_destination")),
                        resultSet.getInt("quantity"),
                        resultSet.getString("terminal_exit_business_id"),
                        resultSet.getString("repair_source_business_id")))
                .list();
    }

    @Override
    public Optional<TerminalExitRow> findTerminalExit(String terminalExitBusinessId) {
        return jdbc.sql("""
                        SELECT terminal_exit_business_id, production_source_business_id, demand_business_id,
                               task_item_business_id, exit_reason, physical_destination, quantity
                        FROM production_terminal_exits WHERE terminal_exit_business_id = :id
                        """)
                .param("id", terminalExitBusinessId)
                .query((resultSet, rowNumber) -> new TerminalExitRow(
                        resultSet.getString("terminal_exit_business_id"),
                        resultSet.getString("production_source_business_id"),
                        resultSet.getString("demand_business_id"),
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("exit_reason"),
                        PhysicalDestination.valueOf(resultSet.getString("physical_destination")),
                        resultSet.getInt("quantity")))
                .optional();
    }

    @Override
    public Optional<ResponsibilityRow> findResponsibilityByDisposition(String dispositionFactBusinessId) {
        return jdbc.sql("""
                        SELECT responsibility_chain_business_id, disposition_fact_business_id,
                               terminal_exit_business_id, responsibility_type,
                               responsible_subject_business_id, waived_reason, quantity, business_date,
                               row_version
                        FROM production_responsibility_facts
                        WHERE disposition_fact_business_id = :id AND is_valid = 1
                        """)
                .param("id", dispositionFactBusinessId)
                .query((resultSet, rowNumber) -> new ResponsibilityRow(
                        resultSet.getString("responsibility_chain_business_id"),
                        resultSet.getString("disposition_fact_business_id"),
                        resultSet.getString("terminal_exit_business_id"),
                        ResponsibilityType.valueOf(resultSet.getString("responsibility_type")),
                        resultSet.getString("responsible_subject_business_id"),
                        resultSet.getString("waived_reason"),
                        resultSet.getInt("quantity"),
                        resultSet.getObject("business_date", LocalDate.class),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public int countTerminalExits(String dispositionFactBusinessId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM production_disposition_facts
                        WHERE fact_business_id = :id AND terminal_exit_business_id IS NOT NULL
                        """)
                .param("id", dispositionFactBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public void invalidateResponsibility(String responsibilityChainBusinessId) {
        jdbc.sql("""
                        UPDATE production_responsibility_facts
                        SET is_valid = 0, row_version = row_version + 1
                        WHERE responsibility_chain_business_id = :id
                        """)
                .param("id", responsibilityChainBusinessId)
                .update();
    }

    @Override
    public void insertResponsibility(ResponsibilityRow row) {
        jdbc.sql("""
                        INSERT INTO production_responsibility_facts (
                            responsibility_chain_business_id, disposition_fact_business_id,
                            terminal_exit_business_id, responsibility_type, responsible_subject_business_id,
                            waived_reason, quantity, business_date, occurred_at, is_valid, row_version)
                        VALUES (:chainId, :dispositionId, :exitId, :type, :subjectId, :waivedReason,
                                :quantity, :businessDate, UTC_TIMESTAMP(6), 1, 1)
                        """)
                .param("chainId", row.responsibilityChainBusinessId())
                .param("dispositionId", row.dispositionFactBusinessId())
                .param("exitId", row.terminalExitBusinessId())
                .param("type", row.responsibilityType().name())
                .param("subjectId", row.responsibleSubjectBusinessId())
                .param("waivedReason", row.waivedReason())
                .param("quantity", row.quantity())
                .param("businessDate", java.sql.Date.valueOf(row.businessDate()))
                .update();
    }

    @Override
    public void insertRevision(RevisionRow row) {
        jdbc.sql("""
                        INSERT INTO production_responsibility_revisions (
                            revision_business_id, responsibility_chain_business_id,
                            disposition_fact_business_id, effect_type, responsibility_type,
                            responsible_subject_business_id, waived_reason, quantity, business_date,
                            reason_text, occurred_at, operator_id)
                        SELECT :revisionId, :chainId, :dispositionId, :effectType, :type, :subjectId,
                               :waivedReason, :quantity, business_date, :reasonText, UTC_TIMESTAMP(6),
                               :operatorId
                        FROM production_responsibility_facts
                        WHERE responsibility_chain_business_id = :chainId
                        """)
                .param("revisionId", row.revisionBusinessId())
                .param("chainId", row.responsibilityChainBusinessId())
                .param("dispositionId", row.dispositionFactBusinessId())
                .param("effectType", row.effectType())
                .param("type", row.responsibilityType().name())
                .param("subjectId", row.responsibleSubjectBusinessId())
                .param("waivedReason", row.waivedReason())
                .param("quantity", row.quantity())
                .param("reasonText", row.reasonText())
                .param("operatorId", "SYSTEM")
                .update();
    }

    @Override
    public List<RevisionRow> findRevisions(String responsibilityChainBusinessId) {
        return jdbc.sql("""
                        SELECT revision_business_id, responsibility_chain_business_id,
                               disposition_fact_business_id, effect_type, responsibility_type,
                               responsible_subject_business_id, waived_reason, quantity, reason_text,
                               feed_sequence
                        FROM production_responsibility_revisions
                        WHERE responsibility_chain_business_id = :chainId OR disposition_fact_business_id = (
                            SELECT disposition_fact_business_id FROM production_responsibility_facts
                            WHERE responsibility_chain_business_id = :chainId)
                        ORDER BY feed_sequence
                        """)
                .param("chainId", responsibilityChainBusinessId)
                .query((resultSet, rowNumber) -> new RevisionRow(
                        resultSet.getString("revision_business_id"),
                        resultSet.getString("responsibility_chain_business_id"),
                        resultSet.getString("disposition_fact_business_id"),
                        resultSet.getString("effect_type"),
                        ResponsibilityType.valueOf(resultSet.getString("responsibility_type")),
                        resultSet.getString("responsible_subject_business_id"),
                        resultSet.getString("waived_reason"),
                        resultSet.getInt("quantity"),
                        resultSet.getString("reason_text"),
                        resultSet.getLong("feed_sequence")))
                .list();
    }

    @Override
    public List<DefectReasonRow> findAllDefectReasons() {
        return jdbc.sql("""
                        SELECT defect_reason_code, name, status, is_other, row_version
                        FROM production_defect_reasons ORDER BY defect_reason_code
                        """)
                .query(JdbcDispositionRepository::mapReason)
                .list();
    }

    @Override
    public List<DefectReasonRow> findActiveDefectReasons() {
        return jdbc.sql("""
                        SELECT defect_reason_code, name, status, is_other, row_version
                        FROM production_defect_reasons WHERE status = 'ACTIVE' ORDER BY defect_reason_code
                        """)
                .query(JdbcDispositionRepository::mapReason)
                .list();
    }

    @Override
    public Optional<DefectReasonRow> findDefectReason(String code) {
        return jdbc.sql("""
                        SELECT defect_reason_code, name, status, is_other, row_version
                        FROM production_defect_reasons WHERE defect_reason_code = :code
                        """)
                .param("code", code)
                .query(JdbcDispositionRepository::mapReason)
                .optional();
    }

    @Override
    public void insertDefectReason(String code, String name, DefectReasonStatus status, boolean other) {
        jdbc.sql("""
                        INSERT INTO production_defect_reasons (
                            defect_reason_code, name, status, is_other, row_version)
                        VALUES (:code, :name, :status, :other, 1)
                        """)
                .param("code", code)
                .param("name", name)
                .param("status", status.name())
                .param("other", other)
                .update();
    }

    @Override
    public boolean updateDefectReason(String code, String name, boolean other, long expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE production_defect_reasons
                        SET name = :name, is_other = :other, row_version = row_version + 1
                        WHERE defect_reason_code = :code AND row_version = :version
                        """)
                .param("name", name)
                .param("other", other)
                .param("code", code)
                .param("version", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public boolean updateDefectReasonStatus(
            String code, DefectReasonStatus status, long expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE production_defect_reasons
                        SET status = :status, row_version = row_version + 1
                        WHERE defect_reason_code = :code AND row_version = :version
                        """)
                .param("status", status.name())
                .param("code", code)
                .param("version", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public Optional<ItemContext> findItemContext(String taskItemBusinessId) {
        return jdbc.sql("""
                        SELECT task_item_business_id, demand_business_id, production_source_business_id
                        FROM production_task_items WHERE task_item_business_id = :id
                        """)
                .param("id", taskItemBusinessId)
                .query((resultSet, rowNumber) -> new ItemContext(
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("demand_business_id"),
                        resultSet.getString("production_source_business_id")))
                .optional();
    }

    @Override
    public List<String> findItemIdsOfSource(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT task_item_business_id FROM production_task_items
                        WHERE production_source_business_id = :id ORDER BY task_item_business_id
                        """)
                .param("id", productionSourceBusinessId)
                .query(String.class)
                .list();
    }

    private static DefectReasonRow mapReason(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new DefectReasonRow(
                resultSet.getString("defect_reason_code"),
                resultSet.getString("name"),
                DefectReasonStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("is_other"),
                resultSet.getLong("row_version"));
    }
}
