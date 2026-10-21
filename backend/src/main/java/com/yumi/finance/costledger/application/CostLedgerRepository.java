package com.yumi.finance.costledger.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 成本台账持久化端口。 */
public interface CostLedgerRepository {

    boolean guardExists(
            String sourceModule, String sourceType, String sourceBusinessId, long sourceVersion);

    void insertGuard(GuardRecord record);

    void insertEntry(EntryRecord record);

    void insertLine(LineRecord record);

    void insertFailure(FailureRecord record);

    java.util.Optional<EntryRecord> findEntry(String costEntryBusinessId);

    void insertAllocation(AllocationRecord record);

    boolean hasAllocations(String costEntryBusinessId);

    void markAllocated(String costEntryBusinessId);

    java.util.Optional<FailureRecord> findFailure(String failureBusinessId);

    void markFailureResolved(String failureBusinessId);

    void incrementFailureAttempts(String failureBusinessId, String lastError);

    record AllocationRecord(
            String allocationBusinessId, String costEntryBusinessId, String subjectType,
            String subjectBusinessId, BigDecimal amount, Instant createdAt) {
    }

    record GuardRecord(
            String sourceModule, String sourceType, String sourceBusinessId, long sourceVersion,
            String costEntryBusinessId, Instant createdAt) {
    }

    record EntryRecord(
            String costEntryBusinessId, String sourceModule, String sourceType,
            String sourceBusinessId, long sourceVersion, String costCategory,
            String subjectType, String subjectBusinessId, BigDecimal amount,
            LocalDate businessDate, String operationType, String basisRef,
            String basisFingerprint, String reasonText, Instant occurredAt, Instant createdAt,
            boolean allocated) {
    }

    record LineRecord(
            String lineBusinessId, String costEntryBusinessId, String componentCode,
            String processCode, String costNature, String bearerType, int quantity,
            BigDecimal unitAmount, BigDecimal amount) {
    }

    record FailureRecord(
            String failureBusinessId, String sourceModule, String sourceType,
            String sourceBusinessId, String reasonText, String status, int attempts,
            String lastError, Instant createdAt, Instant updatedAt) {
    }
}
