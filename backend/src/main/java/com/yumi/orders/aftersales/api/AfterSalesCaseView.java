package com.yumi.orders.aftersales.api;

import com.yumi.platform.api.value.SettlementMoney;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 售后案例详情。 */
public record AfterSalesCaseView(
        String caseBusinessId,
        String caseNo,
        String orderBusinessId,
        String customerBusinessId,
        String issueSummary,
        AfterSalesRouteType routeType,
        AfterSalesCaseStatus status,
        Instant openedAt,
        long contentVersion,
        List<ObligationView> obligations,
        List<AmountDecisionView> amountDecisions,
        List<DeliveryView> deliveries,
        List<ReturnView> returns,
        Instant completedAt) {

    public record ReturnView(
            String receiptItemBusinessId,
            String obligationBusinessId,
            int quantity,
            String conditionCode,
            Instant receivedAt) {
    }

    public record DeliveryView(
            String deliveryFactBusinessId,
            String obligationBusinessId,
            String deliveryType,
            Instant deliveredAt,
            String trackingNo,
            int quantity) {
    }

    public record AmountDecisionView(
            String decisionBusinessId,
            String obligationBusinessId,
            SettlementMoney amount,
            String note,
            Instant decidedAt) {
    }

    public record ObligationView(
            String obligationBusinessId,
            AfterSalesObligationType obligationType,
            AfterSalesBearerType bearerType,
            Integer targetQuantity,
            SettlementMoney targetAmount,
            LocalDate dueDate,
            String note,
            AfterSalesObligationStatus status,
            long rowVersion) {
    }
}
