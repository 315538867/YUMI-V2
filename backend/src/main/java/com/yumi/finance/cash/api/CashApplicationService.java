package com.yumi.finance.cash.api;

/** 现金命令。 */
public interface CashApplicationService {

    CashFactRef receiveOrder(ReceiveOrderPaymentCommand command);

    CashFactRef refundOrder(RefundOrderPaymentCommand command);

    CashFactRef refundAfterSales(RefundAfterSalesPaymentCommand command);

    CashFactRef payPayroll(PayPayrollCommand command);

    DailyCashRecordRef recordDailyCash(RecordDailyCashCommand command);

    CashFactRef returnPayroll(ReturnPayrollPaymentCommand command);

    CashFactRef reverse(ReverseCashFactCommand command);
}
