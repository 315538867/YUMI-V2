package com.yumi.orders.aftersales.api;

/** 售后义务取消与分支核对。 */
public interface AfterSalesObligationChangeService {

    AfterSalesCancellationRef cancel(CancelAfterSalesObligationCommand command);
}
