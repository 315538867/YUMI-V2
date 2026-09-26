package com.yumi.production.verification;

import java.util.List;

/** 批量核验响应：逐明细结果 + 合格流向 + 未完成提醒，任务头状态重新派生。 */
public final class ProductionVerificationViews {

    private ProductionVerificationViews() {
    }

    public record FlowView(String node, int quantity) {
    }

    public record ItemResultView(
            long taskItemId,
            int plannedQuantity,
            int completedQuantity,
            int qualifiedQuantity,
            int reworkQuantity,
            int scrapQuantity,
            int incompleteQuantity,
            List<FlowView> flows,
            Long incompleteReminderId) {
    }

    public record VerificationView(
            long taskId,
            String taskNo,
            String derivedStatus,
            List<ItemResultView> items) {
    }
}
