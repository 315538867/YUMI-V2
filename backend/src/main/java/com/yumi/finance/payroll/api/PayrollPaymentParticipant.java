package com.yumi.finance.payroll.api;

/**
 * 工资支付参与接口：只校验已核定应付并维护支付关联，加入 cash 的同一事务。
 */
public interface PayrollPaymentParticipant {

    PreparedPayrollPayment preparePayment(PayrollPaymentRequest request);

    PayrollPaymentLinkRef commitPaymentLink(CommitPayrollPaymentLink command);

    PayrollPaymentLinkRef reversePaymentLink(ReversePayrollPaymentLink command);

    PreparedPayrollReturn prepareReturn(PayrollReturnRequest request);

    PayrollPaymentReturnRef commitReturn(PayrollReturnRequest request, String returnCashFactId);

    String findPaymentLink(String financeCashFactBusinessId);

    PayrollPaymentReturnRef reverseReturn(
            String originalReturnCashFactId, String reversalCashFactId);
}
