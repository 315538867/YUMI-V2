package com.yumi.finance.payroll.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 工资来源摄取持久化端口。 */
public interface PayrollSourceRepository {

    CursorState laborCursor();

    CursorState responsibilityCursor();

    void updateLaborCursor(Instant occurredAt, String sourceId);

    void updateResponsibilityCursor(long sequence);

    boolean insertLaborEntry(LaborEntry entry);

    boolean insertResponsibilityEntry(ResponsibilityEntry entry);

    void bumpMonthGuard(String employeeBusinessId, LocalDate monthStart);

    List<LaborEntry> laborEntriesBetween(LocalDate start, LocalDate end);

    List<ResponsibilityEntry> responsibilityEntriesBetween(LocalDate start, LocalDate end);

    record CursorState(Instant cursorTime, String cursorSourceId, Long cursorSequence) {
        public static CursorState empty() {
            return new CursorState(null, null, null);
        }
    }

    record LaborEntry(
            String entryBusinessId, String sourceLaborSourceBusinessId, String taskBusinessId,
            String subjectType, String subjectBusinessId, List<String> participants,
            String taskType, String processCode, Integer quantity, BigDecimal unitLaborAmount,
            LocalDate businessDate, Instant occurredAt, String effectiveStatus,
            String reversesSourceId) {
    }

    record ResponsibilityEntry(
            String entryBusinessId, String sourceRevisionBusinessId, long feedSequence,
            String responsibilityChainBusinessId, String dispositionFactBusinessId,
            String effectType, String responsibilityType, String responsibleSubjectBusinessId,
            int quantity, java.math.BigDecimal glueGramsPerPiece,
            java.math.BigDecimal glueUnitPricePerGram, boolean glueEligible,
            LocalDate businessDate, Instant occurredAt) {
    }
}
