package com.yumi.finance.costledger.infrastructure;

import com.yumi.finance.costledger.application.CostLedgerRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 成本台账持久化（MySQL）。 */
@Repository
public class JdbcCostLedgerRepository implements CostLedgerRepository {

    private final JdbcClient jdbc;

    public JdbcCostLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean guardExists(
            String sourceModule, String sourceType, String sourceBusinessId, long sourceVersion) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM finance_cost_source_guards
                        WHERE source_module = :module AND source_type = :type
                          AND source_business_id = :sourceId AND source_version = :version
                        """)
                .param("module", sourceModule)
                .param("type", sourceType)
                .param("sourceId", sourceBusinessId)
                .param("version", sourceVersion)
                .query(Long.class).single() > 0;
    }

    @Override
    public void insertGuard(GuardRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_cost_source_guards (
                            source_module, source_type, source_business_id, source_version,
                            cost_entry_business_id, created_at)
                        VALUES (:module, :type, :sourceId, :version, :entryId, :createdAt)
                        """)
                .param("module", record.sourceModule())
                .param("type", record.sourceType())
                .param("sourceId", record.sourceBusinessId())
                .param("version", record.sourceVersion())
                .param("entryId", record.costEntryBusinessId())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public void insertEntry(EntryRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_cost_entries (
                            cost_entry_business_id, source_module, source_type, source_business_id,
                            source_version, cost_category, subject_type, subject_business_id, amount,
                            business_date, operation_type, basis_ref, basis_fingerprint, reason_text,
                            occurred_at, created_at, allocated)
                        VALUES (:id, :module, :type, :sourceId, :version, :category, :subjectType,
                                :subjectId, :amount, :businessDate, :operation, :basisRef,
                                :basisFingerprint, :reason, :occurredAt, :createdAt, 0)
                        """)
                .param("id", record.costEntryBusinessId())
                .param("module", record.sourceModule())
                .param("type", record.sourceType())
                .param("sourceId", record.sourceBusinessId())
                .param("version", record.sourceVersion())
                .param("category", record.costCategory())
                .param("subjectType", record.subjectType())
                .param("subjectId", record.subjectBusinessId())
                .param("amount", record.amount())
                .param("businessDate", java.sql.Date.valueOf(record.businessDate()))
                .param("operation", record.operationType())
                .param("basisRef", record.basisRef())
                .param("basisFingerprint", record.basisFingerprint())
                .param("reason", record.reasonText())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public void insertLine(LineRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_cost_entry_lines (
                            line_business_id, cost_entry_business_id, component_code, process_code,
                            cost_nature, bearer_type, quantity, unit_amount, amount)
                        VALUES (:id, :entryId, :component, :process, :nature, :bearer, :quantity,
                                :unitAmount, :amount)
                        """)
                .param("id", record.lineBusinessId())
                .param("entryId", record.costEntryBusinessId())
                .param("component", record.componentCode())
                .param("process", record.processCode())
                .param("nature", record.costNature())
                .param("bearer", record.bearerType())
                .param("quantity", record.quantity())
                .param("unitAmount", record.unitAmount())
                .param("amount", record.amount())
                .update();
    }

    @Override
    public void insertFailure(FailureRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_cost_failures (
                            failure_business_id, source_module, source_type, source_business_id,
                            reason_text, status, attempts, last_error, created_at, updated_at)
                        VALUES (:id, :module, :type, :sourceId, :reason, :status, :attempts,
                                :lastError, :createdAt, :updatedAt)
                        """)
                .param("id", record.failureBusinessId())
                .param("module", record.sourceModule())
                .param("type", record.sourceType())
                .param("sourceId", record.sourceBusinessId())
                .param("reason", record.reasonText())
                .param("status", record.status())
                .param("attempts", record.attempts())
                .param("lastError", record.lastError())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .param("updatedAt", JdbcTimes.toUtc(record.updatedAt()))
                .update();
    }

    @Override
    public java.util.Optional<EntryRecord> findEntry(String costEntryBusinessId) {
        return jdbc.sql("""
                        SELECT cost_entry_business_id, source_module, source_type,
                               source_business_id, source_version, cost_category, subject_type,
                               subject_business_id, amount, business_date, operation_type,
                               basis_ref, basis_fingerprint, reason_text, occurred_at, created_at,
                               allocated
                        FROM finance_cost_entries WHERE cost_entry_business_id = :id
                        """)
                .param("id", costEntryBusinessId)
                .query((resultSet, rowNumber) -> new EntryRecord(
                        resultSet.getString("cost_entry_business_id"),
                        resultSet.getString("source_module"),
                        resultSet.getString("source_type"),
                        resultSet.getString("source_business_id"),
                        resultSet.getLong("source_version"),
                        resultSet.getString("cost_category"),
                        resultSet.getString("subject_type"),
                        resultSet.getString("subject_business_id"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getObject("business_date", java.time.LocalDate.class),
                        resultSet.getString("operation_type"),
                        resultSet.getString("basis_ref"),
                        resultSet.getString("basis_fingerprint"),
                        resultSet.getString("reason_text"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class)),
                        resultSet.getBoolean("allocated")))
                .optional();
    }

    @Override
    public void insertAllocation(AllocationRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_cost_allocations (
                            allocation_business_id, cost_entry_business_id, subject_type,
                            subject_business_id, amount, created_at)
                        VALUES (:id, :entryId, :subjectType, :subjectId, :amount, :createdAt)
                        """)
                .param("id", record.allocationBusinessId())
                .param("entryId", record.costEntryBusinessId())
                .param("subjectType", record.subjectType())
                .param("subjectId", record.subjectBusinessId())
                .param("amount", record.amount())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public boolean hasAllocations(String costEntryBusinessId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM finance_cost_allocations
                        WHERE cost_entry_business_id = :id
                        """).param("id", costEntryBusinessId).query(Long.class).single() > 0;
    }

    @Override
    public void markAllocated(String costEntryBusinessId) {
        jdbc.sql("""
                        UPDATE finance_cost_entries SET allocated = 1
                        WHERE cost_entry_business_id = :id
                        """).param("id", costEntryBusinessId).update();
    }

    @Override
    public java.util.Optional<FailureRecord> findFailure(String failureBusinessId) {
        return jdbc.sql("""
                        SELECT failure_business_id, source_module, source_type, source_business_id,
                               reason_text, status, attempts, last_error, created_at, updated_at
                        FROM finance_cost_failures WHERE failure_business_id = :id
                        """)
                .param("id", failureBusinessId)
                .query((resultSet, rowNumber) -> new FailureRecord(
                        resultSet.getString("failure_business_id"),
                        resultSet.getString("source_module"),
                        resultSet.getString("source_type"),
                        resultSet.getString("source_business_id"),
                        resultSet.getString("reason_text"),
                        resultSet.getString("status"),
                        resultSet.getInt("attempts"),
                        resultSet.getString("last_error"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class)),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "updated_at", java.time.LocalDateTime.class))))
                .optional();
    }

    @Override
    public void markFailureResolved(String failureBusinessId) {
        jdbc.sql("""
                        UPDATE finance_cost_failures
                        SET status = 'RESOLVED', attempts = attempts + 1,
                            updated_at = UTC_TIMESTAMP(6)
                        WHERE failure_business_id = :id
                        """).param("id", failureBusinessId).update();
    }

    @Override
    public void incrementFailureAttempts(String failureBusinessId, String lastError) {
        jdbc.sql("""
                        UPDATE finance_cost_failures
                        SET attempts = attempts + 1, last_error = :error,
                            updated_at = UTC_TIMESTAMP(6)
                        WHERE failure_business_id = :id
                        """).param("error", lastError).param("id", failureBusinessId).update();
    }
}
