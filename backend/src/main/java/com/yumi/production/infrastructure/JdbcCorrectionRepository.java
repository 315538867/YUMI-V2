package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.application.port.CorrectionRepository;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 更正命令批次持久化（MySQL）。 */
@Repository
public class JdbcCorrectionRepository implements CorrectionRepository {

    private final JdbcClient jdbc;

    public JdbcCorrectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCorrection(CorrectionRecord record) {
        jdbc.sql("""
                        INSERT INTO production_fact_corrections (
                            correction_command_business_id, task_item_business_id, task_business_id,
                            demand_business_id, production_source_business_id,
                            previous_verification_event_business_id, correction_reason,
                            declared_item_count, command_fingerprint, occurred_at, operator_id)
                        VALUES (:commandId, :itemId, :taskId, :demandId, :sourceId, :previousEventId,
                                :reason, :declaredItemCount, :fingerprint, :occurredAt, :operatorId)
                        """)
                .param("commandId", record.correctionCommandBusinessId())
                .param("itemId", record.taskItemBusinessId())
                .param("taskId", record.taskBusinessId())
                .param("demandId", record.demandBusinessId())
                .param("sourceId", record.productionSourceBusinessId())
                .param("previousEventId", record.previousVerificationEventId())
                .param("reason", record.correctionReason())
                .param("declaredItemCount", record.declaredItemCount())
                .param("fingerprint", record.commandFingerprint())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertItem(ItemRecord record) {
        jdbc.sql("""
                        INSERT INTO production_fact_correction_items (
                            correction_item_business_id, correction_command_business_id, item_index,
                            item_kind, effect_type, referenced_fact_business_id, quantity, amount,
                            business_date)
                        VALUES (:itemId, :commandId, :itemIndex, :itemKind, :effectType, :referencedId,
                                :quantity, :amount, :businessDate)
                        """)
                .param("itemId", record.correctionItemBusinessId())
                .param("commandId", record.correctionCommandBusinessId())
                .param("itemIndex", record.itemIndex())
                .param("itemKind", record.itemKind())
                .param("effectType", record.effectType())
                .param("referencedId", record.referencedFactBusinessId())
                .param("quantity", record.quantity())
                .param("amount", record.amount())
                .param("businessDate", Date.valueOf(record.businessDate()))
                .update();
    }

    @Override
    public void markApplied(String correctionCommandBusinessId, Instant appliedAt) {
        jdbc.sql("""
                        INSERT INTO production_fact_correction_applications (
                            correction_command_business_id, applied_at)
                        VALUES (:commandId, :appliedAt)
                        ON DUPLICATE KEY UPDATE applied_at = applied_at
                        """)
                .param("commandId", correctionCommandBusinessId)
                .param("appliedAt", JdbcTimes.toUtc(appliedAt))
                .update();
    }

    @Override
    public boolean isApplied(String correctionCommandBusinessId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM production_fact_correction_applications
                        WHERE correction_command_business_id = :commandId
                        """)
                .param("commandId", correctionCommandBusinessId)
                .query(Long.class)
                .single() == 1L;
    }

    @Override
    public Optional<CorrectionRecord> findCorrection(String correctionCommandBusinessId) {
        return jdbc.sql("""
                        SELECT correction_command_business_id, task_item_business_id, task_business_id,
                               demand_business_id, production_source_business_id,
                               previous_verification_event_business_id, correction_reason,
                               declared_item_count, command_fingerprint, occurred_at, operator_id,
                               feed_sequence
                        FROM production_fact_corrections
                        WHERE correction_command_business_id = :commandId
                        """)
                .param("commandId", correctionCommandBusinessId)
                .query((resultSet, rowNumber) -> new CorrectionRecord(
                        resultSet.getString("correction_command_business_id"),
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("task_business_id"),
                        resultSet.getString("demand_business_id"),
                        resultSet.getString("production_source_business_id"),
                        resultSet.getString("previous_verification_event_business_id"),
                        resultSet.getString("correction_reason"),
                        resultSet.getInt("declared_item_count"),
                        resultSet.getString("command_fingerprint"),
                        JdbcTimes.toInstant(resultSet.getObject("occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id"),
                        resultSet.getLong("feed_sequence")))
                .optional();
    }

    @Override
    public List<ItemRecord> findItems(String correctionCommandBusinessId) {
        return jdbc.sql("""
                        SELECT correction_item_business_id, correction_command_business_id, item_index,
                               item_kind, effect_type, referenced_fact_business_id, quantity, amount,
                               business_date
                        FROM production_fact_correction_items
                        WHERE correction_command_business_id = :commandId ORDER BY item_index
                        """)
                .param("commandId", correctionCommandBusinessId)
                .query((resultSet, rowNumber) -> new ItemRecord(
                        resultSet.getString("correction_item_business_id"),
                        resultSet.getString("correction_command_business_id"),
                        resultSet.getInt("item_index"),
                        resultSet.getString("item_kind"),
                        resultSet.getString("effect_type"),
                        resultSet.getString("referenced_fact_business_id"),
                        resultSet.getInt("quantity"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getObject("business_date", LocalDate.class)))
                .list();
    }

    @Override
    public List<FeedRow> listAfter(long feedSequence, int limit) {
        return jdbc.sql("""
                        SELECT i.item_index, c.feed_sequence, i.correction_command_business_id,
                               c.task_item_business_id, i.item_kind, i.effect_type,
                               i.referenced_fact_business_id, i.quantity, i.business_date
                        FROM production_fact_correction_items i
                        JOIN production_fact_corrections c
                          ON c.correction_command_business_id = i.correction_command_business_id
                        WHERE c.feed_sequence > :sequence
                        ORDER BY c.feed_sequence, i.item_index
                        LIMIT :limit
                        """)
                .param("sequence", feedSequence)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new FeedRow(
                        resultSet.getLong("feed_sequence"),
                        resultSet.getString("correction_command_business_id"),
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("item_kind"),
                        resultSet.getString("effect_type"),
                        resultSet.getString("referenced_fact_business_id"),
                        resultSet.getInt("quantity"),
                        resultSet.getObject("business_date", LocalDate.class)))
                .list();
    }
}
