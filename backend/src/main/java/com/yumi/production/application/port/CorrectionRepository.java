package com.yumi.production.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 更正命令批次持久化端口。 */
public interface CorrectionRepository {

    void insertCorrection(CorrectionRecord record);

    void insertItem(ItemRecord record);

    void markApplied(String correctionCommandBusinessId, Instant appliedAt);

    boolean isApplied(String correctionCommandBusinessId);

    Optional<CorrectionRecord> findCorrection(String correctionCommandBusinessId);

    List<ItemRecord> findItems(String correctionCommandBusinessId);

    List<FeedRow> listAfter(long feedSequence, int limit);

    /** 更正命令。 */
    record CorrectionRecord(
            String correctionCommandBusinessId, String taskItemBusinessId, String taskBusinessId,
            String demandBusinessId, String productionSourceBusinessId, String previousVerificationEventId,
            String correctionReason, int declaredItemCount, String commandFingerprint,
            Instant occurredAt, String operatorId, long feedSequence) {
    }

    /** 批次项。 */
    record ItemRecord(
            String correctionItemBusinessId, String correctionCommandBusinessId, int itemIndex,
            String itemKind, String effectType, String referencedFactBusinessId, int quantity,
            java.math.BigDecimal amount, LocalDate businessDate) {
    }

    /** Feed 行。 */
    record FeedRow(
            long feedSequence, String correctionCommandBusinessId, String taskItemBusinessId,
            String itemKind, String effectType, String referencedFactBusinessId, int quantity,
            LocalDate businessDate) {
    }
}
