package com.yumi.orders.aftersales.api;

/** 售后金额义务支付参与接口。 */
public interface AfterSalesPaymentParticipant {

    PreparedAfterSalesPayment preparePayment(AfterSalesPaymentRequest request);

    AfterSalesPaymentLinkRef commitPaymentLink(CommitAfterSalesPaymentLink command);

    AfterSalesPaymentLinkRef reversePaymentLink(ReverseAfterSalesPaymentLink command);

    String findPaymentLink(String financeCashFactBusinessId);
}
