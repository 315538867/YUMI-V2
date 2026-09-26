package com.yumi.production.source.internal;

/** rework_sources 一行：返工来源余额 = total_quantity − arranged_quantity。 */
public record ReworkSourceRow(
        Long id,
        String sourceNo,
        long originVerificationId,
        long originTaskItemId,
        long orderId,
        long orderItemId,
        long productId,
        String node,
        int totalQuantity,
        int arrangedQuantity,
        int roundNo,
        Long previousSourceId,
        String reason,
        long version) {

    public int availableQuantity() {
        return totalQuantity - arrangedQuantity;
    }
}
