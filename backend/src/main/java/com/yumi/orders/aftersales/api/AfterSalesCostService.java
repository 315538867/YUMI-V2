package com.yumi.orders.aftersales.api;

/** 售后费用确认、更正及正式成本来源发布。 */
public interface AfterSalesCostService {

    AfterSalesCostRef confirmCost(ConfirmAfterSalesCostCommand command);

    AfterSalesCostRef correctCost(CorrectAfterSalesCostCommand command);
}
