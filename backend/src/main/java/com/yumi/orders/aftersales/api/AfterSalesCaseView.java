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
        Instant completedAt) {

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
