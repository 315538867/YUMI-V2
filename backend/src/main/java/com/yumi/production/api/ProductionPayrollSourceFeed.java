package com.yumi.production.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 工资来源 Feed：劳动与责任各用独立游标，稳定分页并保留更正项。
 */
public interface ProductionPayrollSourceFeed {

    LaborPage laborAfter(LaborCursor cursor, int limit);

    ResponsibilityPage responsibilityAfter(long afterSequence, int limit);

    record LaborCursor(Instant occurredAt, String laborSourceBusinessId) {
        public LaborCursor {
            if (occurredAt == null || laborSourceBusinessId == null
                    || laborSourceBusinessId.isBlank()) {
                throw new IllegalArgumentException("劳动游标必须包含发生时间与来源标识");
            }
        }
    }

    record LaborPage(List<LaborItem> items, LaborCursor nextCursor) {
        public LaborPage {
            items = List.copyOf(items);
        }
    }

    record LaborItem(
            String laborSourceBusinessId,
            String taskBusinessId,
            String subjectType,
            String subjectBusinessId,
            List<String> participantEmployeeIds,
            String taskType,
            String processCode,
            Integer quantity,
            BigDecimal unitLaborAmount,
            LocalDate businessDate,
            Instant occurredAt,
            String effectiveStatus,
            String reversesLaborSourceBusinessId) {
    }

    record ResponsibilityPage(List<ResponsibilityItem> items, Long nextSequence) {
        public ResponsibilityPage {
            items = List.copyOf(items);
        }
    }

    record ResponsibilityItem(
            long feedSequence,
            String revisionBusinessId,
            String responsibilityChainBusinessId,
            String dispositionFactBusinessId,
            String effectType,
            String responsibilityType,
            String responsibleSubjectBusinessId,
            int quantity,
            BigDecimal glueGramsPerPiece,
            BigDecimal glueUnitPricePerGram,
            boolean glueEligible,
            LocalDate businessDate,
            Instant occurredAt) {
    }
}
