package com.yumi.orders.order;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 订单读模型：金额统一以字符串输出（scale4），日期为 ISO 日期。
 * 派生进度（排产/生产/需求处理/发货）由事实计算，不落库为手工状态。
 */
public final class OrderViews {

    private OrderViews() {
    }

    /** 列表摘要。 */
    public record OrderSummary(
            long id,
            String orderNo,
            long customerId,
            String customerName,
            String status,
            LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal receivableAmount,
            long version,
            com.yumi.orders.fulfillment.OrderStatuses.Derived derived) {
    }

    /** 明细读模型。 */
    public record OrderItemView(
            long id,
            int lineNo,
            long productId,
            String productNo,
            String productName,
            int quantity,
            int seamQuantity,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal unitPrice,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal goodsAmount,
            Long seamTypeId,
            String seamTypeName,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamUnitCost,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamFee,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal unitCost,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal goodsCostAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamCostAmount,
            String note) {
    }

    /** 草稿库存计划行（任务 4.8）：带批次当前工序与数量，便于页面判断计划是否已过期。 */
    public record OrderPlanLineView(
            long id,
            long orderItemId,
            int lineNo,
            long batchId,
            String batchNo,
            String node,
            String seamState,
            int batchQuantity,
            int quantity) {
    }

    /** 订单详情：当前有效数据 + 明细 + 金额 + 草稿库存计划。 */
    public record OrderDetail(
            long id,
            String orderNo,
            long customerId,
            String customerName,
            String status,
            LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            String recipientName,
            String recipientPhone,
            String region,
            String address,
            String note,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal goodsAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal discountAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal receivableAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal goodsCostAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal seamCostAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal costAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal profitAmount,
            long version,
            com.yumi.orders.fulfillment.OrderStatuses.Derived derived,
            List<OrderItemView> items,
            List<OrderPlanLineView> inventoryPlan) {
    }
}
