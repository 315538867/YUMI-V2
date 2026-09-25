package com.yumi.orders.shipment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 发货读模型与入参（阶段六）：金额为字符串输出，数量为整数。 */
public final class ShipmentViews {

    private ShipmentViews() {
    }

    /** 来源追溯行：只追溯来源，不再次改变库存。 */
    public record SourceLinkView(
            long id,
            String sourceType,
            long sourceId,
            long sourceLineId,
            int quantity) {
    }

    /** 物流修改历史行。 */
    public record LogisticsChangeView(
            long id,
            String beforeCarrier,
            String afterCarrier,
            String beforeTrackingNo,
            String afterTrackingNo,
            BigDecimal beforeFreight,
            BigDecimal afterFreight,
            String beforeNote,
            String afterNote,
            String reason) {
    }

    /** 发货明细：草稿阶段没有累计/未交付快照。 */
    public record ShipmentItemView(
            long id,
            long orderItemId,
            int lineNo,
            String productNo,
            String productName,
            int quantity,
            String recipientName,
            String recipientPhone,
            String region,
            String address,
            Integer cumulativeShippedQuantity,
            Integer undeliveredQuantity,
            List<SourceLinkView> sourceLinks) {
    }

    /** 发货批次：草稿/已确认/已作废。 */
    public record ShipmentView(
            long id,
            String shipmentNo,
            long orderId,
            String status,
            LocalDate shipmentDate,
            String carrier,
            String trackingNo,
            BigDecimal freight,
            String logisticsNote,
            String currentCarrier,
            String currentTrackingNo,
            BigDecimal currentFreight,
            String currentLogisticsNote,
            String note,
            String confirmedBy,
            String voidReason,
            Long replacesShipmentId,
            boolean afterSalesReplacement,
            long version,
            List<ShipmentItemView> items,
            List<LogisticsChangeView> logisticsChanges) {
    }

    public record ShipmentItemRequest(Long orderItemId, Integer quantity) {
    }

    /** 新建/编辑草稿入参：整批替换明细；草稿不影响可发货与累计发货。 */
    public record ShipmentDraftRequest(
            LocalDate shipmentDate,
            String carrier,
            String trackingNo,
            BigDecimal freight,
            String logisticsNote,
            String note,
            List<ShipmentItemRequest> items) {
    }

    /** 物流修改入参：只允许公司、单号、运费、备注，原因必填。 */
    public record LogisticsChangeRequest(
            String carrier,
            String trackingNo,
            BigDecimal freight,
            String logisticsNote,
            String reason) {
    }

    public record VoidRequest(String reason) {
    }

    public record CorrectionRequest(String reason) {
    }
}
