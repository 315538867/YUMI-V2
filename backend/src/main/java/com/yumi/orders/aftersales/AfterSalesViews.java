package com.yumi.orders.aftersales;

import java.util.List;
import java.util.Map;

/** 售后读模型与入参（阶段八）：数量为整数；可补发/已补发从售后台账汇总派生。 */
public final class AfterSalesViews {

    private AfterSalesViews() {
    }

    public record AfterSalesItemView(
            long id,
            long orderItemId,
            long shipmentItemId,
            String shipmentNo,
            String productNo,
            String productName,
            int seamQuantity,
            int acceptedQuantity,
            int returnedQuantity,
            int replacementRequiredQuantity,
            Integer reworkQuantity,
            Integer scrapQuantity,
            boolean returnVerified,
            int availableQuantity,
            int shippedQuantity,
            int pendingQuantity) {
    }

    public record CorrectionView(
            long id,
            long targetId,
            String beforeValue,
            String afterValue,
            String reason) {
    }

    public record AfterSalesCaseView(
            long id,
            String caseNo,
            long orderId,
            String caseType,
            String status,
            String problem,
            String solution,
            String note,
            List<AfterSalesItemView> items,
            List<Map<String, Object>> refunds,
            List<CorrectionView> corrections,
            List<Long> replacementShipmentIds) {
    }

    public record CreateItemRequest(
            Long shipmentItemId,
            Integer acceptedQuantity,
            Integer returnedQuantity,
            Integer replacementRequiredQuantity) {
    }

    /** 创建售后：来源必须是已确认且有效的原发货批次明细；订单部分发货但未关闭也可创建。 */
    public record CreateAfterSalesRequest(
            String caseType,
            String problem,
            String solution,
            String note,
            List<CreateItemRequest> items) {
    }

    /** 退回核验：退回数量必须等于售后返工 + 售后报废。 */
    public record VerifyReturnRequest(
            Long afterSalesItemId,
            Integer returnedQuantity,
            Integer reworkQuantity,
            Integer scrapQuantity,
            String reason) {
    }

    /** 补发发货：本次补发不得超过可补发与补发需求。 */
    public record ReplacementShipmentRequest(List<ReplacementLine> items) {

        public record ReplacementLine(Long afterSalesItemId, Integer quantity) {
        }
    }

    public record CorrectionRequest(
            String targetType,
            Long targetId,
            String beforeValue,
            String afterValue,
            String reason) {
    }
}
