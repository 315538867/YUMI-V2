package com.yumi.production.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 责任链视图：报废处置对应的唯一有效责任及更正历史。
 *
 * <p>缺陷事实与责任事实分别可追溯：本视图只描述责任维度，不改变缺陷、处置或物理退出。
 */
public record ResponsibilityView(
        String responsibilityChainBusinessId,
        String dispositionFactBusinessId,
        String terminalExitBusinessId,
        ResponsibilityType responsibilityType,
        String responsibleSubjectBusinessId,
        String waivedReason,
        int quantity,
        LocalDate businessDate,
        long rowVersion,
        List<RevisionView> revisions) {

    public ResponsibilityView {
        revisions = List.copyOf(revisions);
    }

    /** 责任更正记录。 */
    public record RevisionView(
            String revisionBusinessId,
            String effectType,
            ResponsibilityType responsibilityType,
            int quantity,
            String reasonText,
            long feedSequence) {
    }
}
