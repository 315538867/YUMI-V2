package com.yumi.orders.aftersales.api;

/**
 * 售后退回与交付命令：退回按最近交付追溯，原样退还同事务写池消费与售后交付。
 */
public interface AfterSalesReturnService {

    ReceiveReturnRef receiveReturn(ReceiveReturnCommand command);

    AfterSalesDeliveryRef returnToCustomer(ReturnToCustomerCommand command);

    AfterSalesDeliveryRef deliver(ConfirmAfterSalesDeliveryCommand command);
}
