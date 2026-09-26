package com.yumi.production.source;

import java.util.List;

/** 返工来源响应：发生工序、轮次、父子链、总量与余额。 */
public final class ReworkSourceViews {

    private ReworkSourceViews() {
    }

    public record ReworkSourceView(
            long id,
            String sourceNo,
            long originVerificationId,
            long originTaskItemId,
            long orderId,
            long orderItemId,
            long productId,
            String node,
            int totalQuantity,
            int arrangedQuantity,
            int availableQuantity,
            int roundNo,
            Long previousSourceId,
            String reason) {
    }

    public record ReworkSourceList(List<ReworkSourceView> items) {
    }
}
