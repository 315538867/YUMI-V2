package com.yumi.orders.api;

import com.yumi.customer.api.DeliveryContactOverride;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建并确认订单命令：一次提交即生成正式订单，不保存草稿。
 *
 * @param idempotencyKey 幂等键；正式命令必须携带，重试复用已成功结果
 * @param customerBusinessId 客户编号
 * @param contactOverride 本次收货覆盖值；不修改客户默认地址
 * @param orderDate 订单日期
 * @param expectedDeliveryDate 预计交付日期
 * @param note 订单说明；选填，不参与履约计算
 * @param items 订单项；每项一个交付约定组与一个生产需求
 */
public record CreateConfirmedOrderCommand(
        String idempotencyKey,
        String customerBusinessId,
        DeliveryContactOverride contactOverride,
        LocalDate orderDate,
        LocalDate expectedDeliveryDate,
        String note,
        List<OrderItemRequest> items) {

    public CreateConfirmedOrderCommand(
            String idempotencyKey,
            String customerBusinessId,
            DeliveryContactOverride contactOverride,
            LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            List<OrderItemRequest> items) {
        this(idempotencyKey, customerBusinessId, contactOverride, orderDate, expectedDeliveryDate,
                null, items);
    }

    public CreateConfirmedOrderCommand {
        items = List.copyOf(items == null ? List.of() : items);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("正式订单命令必须携带幂等键");
        }
        if (customerBusinessId == null || customerBusinessId.isBlank()) {
            throw new IllegalArgumentException("订单必须选择客户");
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("订单必须至少有一个订单项");
        }
        if (orderDate == null || expectedDeliveryDate == null) {
            throw new IllegalArgumentException("订单日期与预计交付日期不能为空");
        }
        if (expectedDeliveryDate.isBefore(orderDate)) {
            throw new IllegalArgumentException("预计交付日期不能早于订单日期");
        }
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("订单说明不能超过 500 字");
        }
    }
}
