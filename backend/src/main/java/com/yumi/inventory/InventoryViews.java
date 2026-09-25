package com.yumi.inventory;

import java.time.LocalDate;
import java.util.List;

/** 库存读模型：批次、汇总、流水与推荐（任务 4.2/4.5）。 */
public final class InventoryViews {

    private InventoryViews() {
    }

    public record BatchView(
            long id,
            String batchNo,
            long productId,
            String productNo,
            String productName,
            String sourceType,
            String node,
            String seamState,
            int quantity,
            LocalDate inventoryDate,
            String note,
            long version) {
    }

    /** 汇总行：商品 + 工序 + 缝边状态 的当前数量与批次数。 */
    public record SummaryView(
            long productId,
            String productNo,
            String productName,
            String node,
            String seamState,
            int quantity,
            int batchCount) {
    }

    /** 流水行：含变动前后数量与来源业务追溯。 */
    public record MovementLineView(
            long id,
            long batchId,
            String batchNo,
            String direction,
            int quantity,
            int quantityBefore,
            int quantityAfter,
            long productId,
            String productNo,
            String productName,
            String node,
            String seamState,
            Long orderItemId,
            Integer orderLineNo,
            String orderNo,
            String note) {
    }

    /** 流水：业务头 + 明细行。 */
    public record MovementView(
            long id,
            String movementNo,
            String movementType,
            LocalDate businessDate,
            String sourceType,
            Long reversesMovementId,
            String reason,
            String operatorUsername,
            String note,
            List<MovementLineView> lines) {
    }

    /** 领用明细：批次、订单明细、数量、接入节点与对应流水/履约记录（用于出库前后数量与追溯）。 */
    public record AllocationLineView(
            long id,
            long batchId,
            String batchNo,
            long orderItemId,
            Integer orderLineNo,
            String orderNo,
            int quantity,
            String targetNode,
            long movementLineId,
            int quantityBefore,
            int quantityAfter,
            long fulfillmentEntryId) {
    }

    /** 领用：业务头 + 明细行。 */
    public record AllocationView(
            long id,
            long orderId,
            String orderNo,
            String status,
            String reason,
            String cancelledBy,
            String cancelReason,
            long version,
            List<AllocationLineView> lines) {
    }



    /** 推荐批次：只推荐不占用，支持一次选择多个批次。 */
    public record RecommendationView(
            long batchId,
            String batchNo,
            long productId,
            String productNo,
            String productName,
            String node,
            String seamState,
            int quantity,
            LocalDate inventoryDate,
            List<String> allowedTargets) {
    }
}
