package com.yumi.production.api;

import java.time.LocalDate;

/** 更正 Feed 的一项：劳动、材料或责任的 REVERSE / APPLY。 */
public record ProductionCorrectionFeedItem(
        long feedSequence,
        String correctionCommandBusinessId,
        String taskItemBusinessId,
        String itemKind,
        String effectType,
        String referencedFactBusinessId,
        int quantity,
        LocalDate businessDate) {
}
