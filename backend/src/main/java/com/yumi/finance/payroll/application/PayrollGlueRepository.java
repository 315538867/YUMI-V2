package com.yumi.finance.payroll.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 胶水策略与自然月参考持久化端口。 */
public interface PayrollGlueRepository {

    long lockGuardAndReadVersion();

    long readGuardVersion();

    void updateGuardVersion(long version);

    long nextPolicyVersion(LocalDate effectiveMonth);

    void insertPolicy(PolicyRecord record);

    Optional<PolicyRecord> findPolicyById(String policyId);

    Optional<PolicyRecord> findPolicyForMonth(LocalDate monthStart);

    List<GlueSourceLine> listEffectiveEmployeeGlueLines(
            String employeeBusinessId, LocalDate monthStart, LocalDate monthEnd);

    long currentSourceVersion(String employeeBusinessId, LocalDate monthStart, LocalDate monthEnd);

    void upsertReference(ReferenceRecord record);

    void deleteLines(String referenceBusinessId);

    Optional<ReferenceRecord> findReference(String employeeBusinessId, LocalDate monthStart);

    void insertLine(LineRecord record);

    List<LineRecord> listLines(String referenceBusinessId);

    record PolicyRecord(
            String policyBusinessId, LocalDate effectiveMonth, long version,
            BigDecimal exemptWeightGrams, String reasonText, Instant createdAt, String operatorId) {
    }

    record GlueSourceLine(
            String responsibilityChainBusinessId, String dispositionFactBusinessId,
            int quantity, BigDecimal glueGramsPerPiece, BigDecimal glueUnitPricePerGram,
            LocalDate businessDate, long feedSequence) {
    }

    record ReferenceRecord(
            String referenceBusinessId, String employeeBusinessId, LocalDate monthStart,
            String policyBusinessId, String status, String incompleteReason,
            BigDecimal allocatedWeight, BigDecimal exemptWeight, String fingerprint,
            long sourceVersion, Instant computedAt) {
    }

    record LineRecord(
            String lineBusinessId, String referenceBusinessId,
            String responsibilityChainBusinessId, String costLineKey, LocalDate businessDate,
            BigDecimal allocatedWeight, BigDecimal exemptWeight, BigDecimal chargeableWeight,
            BigDecimal glueUnitPrice, BigDecimal preAmount, BigDecimal postAmount, Instant occurredAt) {
    }
}
