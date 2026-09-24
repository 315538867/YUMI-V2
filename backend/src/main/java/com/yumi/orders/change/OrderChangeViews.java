package com.yumi.orders.change;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 订单变更读模型：表头变更后值 + 结构化前后值 + 超出处理方案（金额 scale4 字符串）。 */
public final class OrderChangeViews {

    private OrderChangeViews() {
    }

    public record ChangeItemView(
            long id,
            Long orderItemId,
            String changeType,
            Integer lineNo,
            Long productId,
            Integer beforeQuantity,
            Integer afterQuantity,
            Integer beforeSeamQuantity,
            Integer afterSeamQuantity,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal beforeUnitPrice,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal afterUnitPrice,
            Long beforeSeamTypeId,
            Long afterSeamTypeId,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal beforeSeamFee,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal afterSeamFee,
            String beforeNote,
            String afterNote,
            String surplusDisposition,
            Integer surplusQuantity,
            String surplusReason) {
    }

    /** 变更确认结果：变更单 + 应用后的订单（含新投影派生状态）。 */
    public record ConfirmResult(ChangeView change, com.yumi.orders.order.OrderViews.OrderDetail order) {
    }

    public record ChangeView(
            long id,
            String changeNo,
            long orderId,
            String orderNo,
            String status,
            String reason,
            LocalDate newExpectedDeliveryDate,
            String newRecipientName,
            String newRecipientPhone,
            String newRegion,
            String newAddress,
            String newNote,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal newDiscountAmount,
            long version,
            List<ChangeItemView> items) {
    }
}
