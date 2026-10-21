package com.yumi.finance.payroll.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.finance.payroll.application.PayrollSourceRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 工资来源摄取持久化（MySQL）。 */
@Repository
public class JdbcPayrollSourceRepository implements PayrollSourceRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPayrollSourceRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public CursorState laborCursor() {
        return jdbc.sql("""
                        SELECT cursor_time, cursor_source_id, cursor_sequence
                        FROM finance_payroll_source_cursors WHERE feed_code = 'LABOR'
                        """)
                .query((resultSet, rowNumber) -> new CursorState(
                        JdbcTimes.toInstant(resultSet.getObject(
                                "cursor_time", java.time.LocalDateTime.class)),
                        resultSet.getString("cursor_source_id"),
                        (Long) resultSet.getObject("cursor_sequence")))
                .optional()
                .orElse(CursorState.empty());
    }

    @Override
    public CursorState responsibilityCursor() {
        return jdbc.sql("""
                        SELECT cursor_time, cursor_source_id, cursor_sequence
                        FROM finance_payroll_source_cursors WHERE feed_code = 'RESPONSIBILITY'
                        """)
                .query((resultSet, rowNumber) -> new CursorState(
                        JdbcTimes.toInstant(resultSet.getObject(
                                "cursor_time", java.time.LocalDateTime.class)),
                        resultSet.getString("cursor_source_id"),
                        (Long) resultSet.getObject("cursor_sequence")))
                .optional()
                .orElse(CursorState.empty());
    }

    @Override
    public void updateLaborCursor(java.time.Instant occurredAt, String sourceId) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_source_cursors (
                            feed_code, cursor_time, cursor_source_id, updated_at)
                        VALUES ('LABOR', :time, :sourceId, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE cursor_time = VALUES(cursor_time),
                            cursor_source_id = VALUES(cursor_source_id),
                            updated_at = UTC_TIMESTAMP(6)
                        """)
                .param("time", JdbcTimes.toUtc(occurredAt))
                .param("sourceId", sourceId)
                .update();
    }

    @Override
    public void updateResponsibilityCursor(long sequence) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_source_cursors (
                            feed_code, cursor_sequence, updated_at)
                        VALUES ('RESPONSIBILITY', :sequence, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE cursor_sequence = VALUES(cursor_sequence),
                            updated_at = UTC_TIMESTAMP(6)
                        """)
                .param("sequence", sequence)
                .update();
    }

    @Override
    public boolean insertLaborEntry(LaborEntry entry) {
        int inserted = jdbc.sql("""
                        INSERT IGNORE INTO finance_payroll_labor_entries (
                            entry_business_id, source_labor_source_business_id, task_business_id,
                            subject_type, subject_business_id, participants_json, task_type,
                            process_code, quantity, unit_labor_amount, business_date, occurred_at,
                            effective_status, reverses_source_id, ingested_at)
                        VALUES (:id, :sourceId, :taskId, :subjectType, :subjectId, :participants,
                                :taskType, :processCode, :quantity, :unitAmount, :businessDate,
                                :occurredAt, :status, :reversesId, UTC_TIMESTAMP(6))
                        """)
                .param("id", entry.entryBusinessId())
                .param("sourceId", entry.sourceLaborSourceBusinessId())
                .param("taskId", entry.taskBusinessId())
                .param("subjectType", entry.subjectType())
                .param("subjectId", entry.subjectBusinessId())
                .param("participants", writeJson(entry.participants()))
                .param("taskType", entry.taskType())
                .param("processCode", entry.processCode())
                .param("quantity", entry.quantity())
                .param("unitAmount", entry.unitLaborAmount())
                .param("businessDate", java.sql.Date.valueOf(entry.businessDate()))
                .param("occurredAt", JdbcTimes.toUtc(entry.occurredAt()))
                .param("status", entry.effectiveStatus())
                .param("reversesId", entry.reversesSourceId())
                .update();
        return inserted == 1;
    }

    @Override
    public boolean insertResponsibilityEntry(ResponsibilityEntry entry) {
        int inserted = jdbc.sql("""
                        INSERT IGNORE INTO finance_payroll_responsibility_entries (
                            entry_business_id, source_revision_business_id, feed_sequence,
                            responsibility_chain_business_id, disposition_fact_business_id,
                            effect_type, responsibility_type, responsible_subject_business_id,
                            quantity, glue_grams_per_piece, glue_unit_price_per_gram,
                            glue_eligible, business_date, occurred_at, ingested_at)
                        VALUES (:id, :revisionId, :sequence, :chainId, :dispositionId, :effect,
                                :type, :subjectId, :quantity, :glueGrams, :gluePrice, :glueEligible,
                                :businessDate, :occurredAt, UTC_TIMESTAMP(6))
                        """)
                .param("id", entry.entryBusinessId())
                .param("revisionId", entry.sourceRevisionBusinessId())
                .param("sequence", entry.feedSequence())
                .param("chainId", entry.responsibilityChainBusinessId())
                .param("dispositionId", entry.dispositionFactBusinessId())
                .param("effect", entry.effectType())
                .param("type", entry.responsibilityType())
                .param("subjectId", entry.responsibleSubjectBusinessId())
                .param("quantity", entry.quantity())
                .param("glueGrams", entry.glueGramsPerPiece())
                .param("gluePrice", entry.glueUnitPricePerGram())
                .param("glueEligible", entry.glueEligible())
                .param("businessDate", java.sql.Date.valueOf(entry.businessDate()))
                .param("occurredAt", JdbcTimes.toUtc(entry.occurredAt()))
                .update();
        return inserted == 1;
    }

    @Override
    public void bumpMonthGuard(String employeeBusinessId, LocalDate monthStart) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_month_guards (
                            employee_business_id, month_start, source_version, row_version)
                        VALUES (:employeeId, :monthStart, 1, 1)
                        ON DUPLICATE KEY UPDATE source_version = source_version + 1,
                            row_version = row_version + 1
                        """)
                .param("employeeId", employeeBusinessId)
                .param("monthStart", java.sql.Date.valueOf(monthStart))
                .update();
    }

    @Override
    public List<LaborEntry> laborEntriesBetween(LocalDate start, LocalDate end) {
        return jdbc.sql("""
                        SELECT entry_business_id, source_labor_source_business_id, task_business_id,
                               subject_type, subject_business_id, participants_json, task_type,
                               process_code, quantity, unit_labor_amount, business_date, occurred_at,
                               effective_status, reverses_source_id
                        FROM finance_payroll_labor_entries
                        WHERE business_date BETWEEN :start AND :end
                        ORDER BY business_date, occurred_at, source_labor_source_business_id
                        """)
                .param("start", java.sql.Date.valueOf(start))
                .param("end", java.sql.Date.valueOf(end))
                .query((resultSet, rowNumber) -> new LaborEntry(
                        resultSet.getString("entry_business_id"),
                        resultSet.getString("source_labor_source_business_id"),
                        resultSet.getString("task_business_id"),
                        resultSet.getString("subject_type"),
                        resultSet.getString("subject_business_id"),
                        readParticipants(resultSet.getString("participants_json")),
                        resultSet.getString("task_type"),
                        resultSet.getString("process_code"),
                        (Integer) resultSet.getObject("quantity"),
                        resultSet.getBigDecimal("unit_labor_amount"),
                        resultSet.getObject("business_date", LocalDate.class),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("effective_status"),
                        resultSet.getString("reverses_source_id")))
                .list();
    }

    @Override
    public List<ResponsibilityEntry> responsibilityEntriesBetween(LocalDate start, LocalDate end) {
        return jdbc.sql("""
                        SELECT entry_business_id, source_revision_business_id, feed_sequence,
                               responsibility_chain_business_id, disposition_fact_business_id,
                               effect_type, responsibility_type, responsible_subject_business_id,
                               quantity, glue_grams_per_piece, glue_unit_price_per_gram,
                               glue_eligible, business_date, occurred_at
                        FROM finance_payroll_responsibility_entries
                        WHERE business_date BETWEEN :start AND :end
                        ORDER BY business_date, feed_sequence
                        """)
                .param("start", java.sql.Date.valueOf(start))
                .param("end", java.sql.Date.valueOf(end))
                .query((resultSet, rowNumber) -> new ResponsibilityEntry(
                        resultSet.getString("entry_business_id"),
                        resultSet.getString("source_revision_business_id"),
                        resultSet.getLong("feed_sequence"),
                        resultSet.getString("responsibility_chain_business_id"),
                        resultSet.getString("disposition_fact_business_id"),
                        resultSet.getString("effect_type"),
                        resultSet.getString("responsibility_type"),
                        resultSet.getString("responsible_subject_business_id"),
                        resultSet.getInt("quantity"),
                        resultSet.getBigDecimal("glue_grams_per_piece"),
                        resultSet.getBigDecimal("glue_unit_price_per_gram"),
                        resultSet.getBoolean("glue_eligible"),
                        resultSet.getObject("business_date", LocalDate.class),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class))))
                .list();
    }

    private String writeJson(List<String> participants) {
        try {
            return objectMapper.writeValueAsString(participants);
        } catch (Exception exception) {
            throw new IllegalStateException("工资参与人无法序列化", exception);
        }
    }

    private List<String> readParticipants(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("工资参与人无法解析", exception);
        }
    }
}
