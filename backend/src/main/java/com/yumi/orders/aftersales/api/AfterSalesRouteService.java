package com.yumi.orders.aftersales.api;

/**
 * 售后退回制品的修复与补发路线。
 */
public interface AfterSalesRouteService {

    AfterSalesRepairRef requestRepair(RequestRepairCommand command);

    AfterSalesObligationChangeRef changeObligation(ChangeAfterSalesObligationCommand command);
}
