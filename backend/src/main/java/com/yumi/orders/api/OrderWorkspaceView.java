package com.yumi.orders.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.Instant;
import java.util.List;

/** 订单详情工作区只读视图：订单、发货、支付关联与订单时间线。 */
public record OrderWorkspaceView(
        OrderView order,
        List<ShipmentView> shipments,
        List<PaymentLinkView> payments,
        List<EventView> events) {

    public OrderWorkspaceView {
        shipments = List.copyOf(shipments);
        payments = List.copyOf(payments);
        events = List.copyOf(events);
    }

    /** 发货记录。 */
    public record ShipmentView(
            String shipmentBusinessId,
            String shipmentNo,
            Instant shippedAt,
            String trackingNo,
            boolean valid,
            List<ShipmentItemView> items,
            List<CorrectionView> corrections) {

        public ShipmentView {
            items = List.copyOf(items);
            corrections = List.copyOf(corrections);
        }
    }

    /** 发货明细及有效数量。 */
    public record ShipmentItemView(
            String shipmentItemBusinessId,
            String orderItemBusinessId,
            String productionSourceBusinessId,
            int quantity,
            int effectiveQuantity) {
    }

    /** 发货更正事实。 */
    public record CorrectionView(
            String factBusinessId,
            String shipmentItemBusinessId,
            String correctionType,
            int quantityDelta,
            String reasonText,
            Instant occurredAt,
            boolean valid) {
    }

    /** 订单支付关联；凭证与现金详情按现金事实标识另行读取。 */
    public record PaymentLinkView(
            String factBusinessId,
            String financeCashFactBusinessId,
            String linkType,
            SettlementMoney amount,
            String reversesFactBusinessId,
            boolean valid,
            Instant occurredAt) {
    }

    /** 订单时间线事件；code 为可选业务编码，由前端按展示词典翻译。 */
    public record EventView(
            String kind,
            String code,
            Instant occurredAt,
            String title,
            String description) {
    }
}
