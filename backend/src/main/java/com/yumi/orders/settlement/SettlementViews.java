package com.yumi.orders.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 收退款与结清读模型（阶段七）：金额为字符串输出，收款状态由事实派生。 */
public final class SettlementViews {

    private SettlementViews() {
    }

    public record PaymentView(
            long id,
            String paymentNo,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal amount,
            LocalDate businessDate,
            String method,
            String note,
            String operatorUsername) {
    }

    public record RefundView(
            long id,
            String refundNo,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal amount,
            LocalDate businessDate,
            String method,
            String reason,
            String note,
            String sourceType,
            long sourceId,
            String operatorUsername) {
    }

    /** 关闭条件逐项：便于页面把「为什么还不能关闭」直接展示出来。 */
    public record CloseConditionView(String name, boolean satisfied, String detail) {
    }

    public record SettlementView(
            long orderId,
            String orderNo,
            String orderStatus,
            String receivableStatus,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal paidAmount,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal changeRefundAmount,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
            BigDecimal afterSalesRefundAmount,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal netSettledAmount,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal actualNetReceived,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
            BigDecimal effectiveReceivableAmount,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal refundPendingAmount,
            List<PaymentView> payments,
            List<RefundView> refunds,
            List<CloseConditionView> closeConditions) {
    }

    public record PaymentRequest(
            BigDecimal amount,
            LocalDate businessDate,
            String method,
            String note) {
    }

    /** 退款必须关联来源：`ORDER_CHANGE` 关联变更单，`AFTER_SALES` 关联售后单。 */
    public record RefundRequest(
            BigDecimal amount,
            LocalDate businessDate,
            String method,
            String reason,
            String note,
            String sourceType,
            Long sourceId) {
    }
}
