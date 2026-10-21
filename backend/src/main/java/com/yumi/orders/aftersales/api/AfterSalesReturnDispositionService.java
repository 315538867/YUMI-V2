package com.yumi.orders.aftersales.api;

/** 售后池初次处置由 orders 编排，production 仅在同一事务参与。 */
public interface AfterSalesReturnDispositionService {

    ReturnDispositionRef disposeReturnedProduct(DisposeReturnedProductCommand command);
}
