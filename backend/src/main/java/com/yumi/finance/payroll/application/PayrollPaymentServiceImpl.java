package com.yumi.finance.payroll.application;

import com.yumi.finance.payroll.api.CommitPayrollPaymentLink;
import com.yumi.finance.payroll.api.PayrollPaymentLinkRef;
import com.yumi.finance.payroll.api.PayrollPaymentParticipant;
import com.yumi.finance.payroll.api.PayrollPaymentRequest;
import com.yumi.finance.payroll.api.PreparedPayrollReturn;
import com.yumi.finance.payroll.api.PayrollPaymentReturnRef;
import com.yumi.finance.payroll.api.PayrollReturnRequest;
import com.yumi.finance.payroll.api.PreparedPayrollPayment;
import com.yumi.finance.payroll.api.ReversePayrollPaymentLink;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工资支付参与实现（基线 13 §8.2）。
 *
 * <p>只允许已核定员工在剩余应付内支付；加入 cash 同一事务，不依赖 cash 实现或表。
 */
public class PayrollPaymentServiceImpl implements PayrollPaymentParticipant {

    private final PayrollRepository payroll;
    private final CurrentActorProvider currentActor;
    private final Clock clock;

    public PayrollPaymentServiceImpl(
            PayrollRepository payroll, CurrentActorProvider currentActor, Clock clock) {
        this.payroll = payroll;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedPayrollPayment preparePayment(PayrollPaymentRequest request) {
        PayrollRepository.ResultRecord result = lockAndValidate(request);
        BigDecimal paid = payroll.paidAmount(result.resultBusinessId());
        return new PreparedPayrollPayment(
                result.resultBusinessId(), result.employeeBusinessId(), request.amount(),
                SettlementMoney.of(paid, CurrencyCode.CNY),
                SettlementMoney.of(result.payableAmount(), CurrencyCode.CNY));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PayrollPaymentLinkRef commitPaymentLink(CommitPayrollPaymentLink command) {
        PreparedPayrollPayment prepared = command.prepared();
        PayrollRepository.ResultRecord result = lockAndValidate(
                new PayrollPaymentRequest(
                        prepared.resultBusinessId(), prepared.amount(),
                        command.financeCashFactBusinessId()));
        String linkId = "PPL-" + UUID.randomUUID();
        try {
            payroll.insertPaymentLink(new PayrollRepository.PaymentLinkRecord(
                    linkId, result.resultBusinessId(), command.financeCashFactBusinessId(),
                    prepared.amount().value(), null, null, clock.instant(), operatorId()));
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(
                    ApiErrorType.IDEMPOTENCY_CONFLICT, "PAYROLL_PAYMENT_DUPLICATED",
                    "同一现金事实不能重复关联同一员工工资");
        }
        BigDecimal paid = payroll.paidAmount(result.resultBusinessId())
                .add(prepared.amount().value());
        payroll.upsertPaidAmount(result.resultBusinessId(), paid);
        return new PayrollPaymentLinkRef(
                linkId, result.resultBusinessId(), prepared.amount(),
                SettlementMoney.of(paid, CurrencyCode.CNY),
                SettlementMoney.of(result.payableAmount().subtract(paid), CurrencyCode.CNY),
                false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PayrollPaymentLinkRef reversePaymentLink(ReversePayrollPaymentLink command) {
        var original = payroll.findPaymentLink(command.linkBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_PAYMENT_NOT_FOUND", "工资支付关联不存在"));
        if (original.reversesLinkBusinessId() != null) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_PAYMENT_REVERSAL_INVALID",
                    "反向关联不能再被反向");
        }
        var result = payroll.lockResultById(original.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        if (payroll.findReversalOf(original.linkBusinessId()).isPresent()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_PAYMENT_ALREADY_REVERSED",
                    "该工资支付已经反向，不能重复冲销");
        }
        BigDecimal paid = payroll.paidAmount(result.resultBusinessId())
                .subtract(original.amount());
        if (paid.signum() < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PAYROLL_PAYMENT_INCONSISTENT",
                    "反向工资支付导致已付净额为负");
        }
        String reversalId = "PPL-" + UUID.randomUUID();
        payroll.insertPaymentLink(new PayrollRepository.PaymentLinkRecord(
                reversalId, result.resultBusinessId(), command.financeCashFactBusinessId(),
                original.amount(), original.linkBusinessId(), command.reasonText().strip(),
                clock.instant(), operatorId()));
        payroll.upsertPaidAmount(result.resultBusinessId(), paid);
        return new PayrollPaymentLinkRef(
                reversalId, result.resultBusinessId(),
                SettlementMoney.of(original.amount(), CurrencyCode.CNY),
                SettlementMoney.of(paid, CurrencyCode.CNY),
                SettlementMoney.of(result.payableAmount().subtract(paid), CurrencyCode.CNY),
                true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedPayrollReturn prepareReturn(PayrollReturnRequest request) {
        if (request == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PAYROLL_RETURN", "工资退回命令不能为空");
        }
        var result = payroll.lockResultById(request.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        if (!"CONFIRMED".equals(result.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_NOT_CONFIRMED",
                    "只有已核定员工可以退回工资");
        }
        BigDecimal paid = payroll.paidAmount(result.resultBusinessId());
        if (paid.compareTo(request.amount().value()) < 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_RETURN_EXCEEDS_PAID",
                    "退回超过该员工已付净额：已付 " + paid.setScale(2).toPlainString());
        }
        return new PreparedPayrollReturn(
                result.resultBusinessId(), request.amount(),
                SettlementMoney.of(paid, CurrencyCode.CNY));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PayrollPaymentReturnRef commitReturn(
            PayrollReturnRequest request, String returnCashFactId) {
        var prepared = prepareReturn(request);
        BigDecimal paid = payroll.paidAmount(prepared.resultBusinessId())
                .subtract(prepared.amount().value());
        payroll.insertPaymentReturn(new PayrollRepository.PaymentReturnRecord(
                "PPR-" + UUID.randomUUID(), prepared.resultBusinessId(),
                request.originalFinanceCashFactId(), returnCashFactId, prepared.amount().value(),
                "ACCRUE", null, true));
        payroll.upsertPaidAmount(prepared.resultBusinessId(), paid);
        return new PayrollPaymentReturnRef(
                prepared.resultBusinessId(), prepared.amount(),
                SettlementMoney.of(paid, CurrencyCode.CNY));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String findPaymentLink(String financeCashFactBusinessId) {
        return payroll.findPaymentLinkByCashFact(financeCashFactBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_PAYMENT_NOT_FOUND", "工资支付关联不存在"))
                .linkBusinessId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PayrollPaymentReturnRef reverseReturn(
            String originalReturnCashFactId, String reversalCashFactId) {
        var original = payroll.findPaymentReturnByCashFact(originalReturnCashFactId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RETURN_NOT_FOUND", "工资退回关联不存在"));
        if (!"ACCRUE".equals(original.operationType()) || !original.valid()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_RETURN_REVERSAL_INVALID",
                    "该工资退回已经反向");
        }
        if (payroll.findReturnReversal(original.returnLinkBusinessId()).isPresent()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_RETURN_ALREADY_REVERSED",
                    "该工资退回已经反向");
        }
        var result = payroll.lockResultById(original.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        BigDecimal paid = payroll.paidAmount(result.resultBusinessId()).add(original.amount());
        payroll.insertPaymentReturn(new PayrollRepository.PaymentReturnRecord(
                "PPR-" + UUID.randomUUID(), original.resultBusinessId(),
                original.originalFinanceCashFactId(), reversalCashFactId, original.amount(),
                "REVERSE", original.returnLinkBusinessId(), true));
        payroll.upsertPaidAmount(result.resultBusinessId(), paid);
        return new PayrollPaymentReturnRef(
                result.resultBusinessId(),
                SettlementMoney.of(original.amount(), CurrencyCode.CNY),
                SettlementMoney.of(paid, CurrencyCode.CNY));
    }

    private PayrollRepository.ResultRecord lockAndValidate(PayrollPaymentRequest request) {
        if (request == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PAYROLL_PAYMENT", "工资支付命令不能为空");
        }
        var result = payroll.lockResultById(request.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        if (!"CONFIRMED".equals(result.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_NOT_CONFIRMED",
                    "只有已核定员工可以支付工资");
        }
        if (result.payableAmount().signum() <= 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_PAYMENT_NOT_ALLOWED",
                    "零工资不生成付款");
        }
        BigDecimal remaining = result.payableAmount().subtract(payroll.paidAmount(
                result.resultBusinessId()));
        if (request.amount().value().compareTo(remaining) > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_PAYMENT_EXCEEDS_PAYABLE",
                    "付款超过剩余应付：可付 " + remaining.setScale(2).toPlainString());
        }
        return result;
    }

    private String operatorId() {
        return currentActor.requireCurrentActor().accountBusinessId().value();
    }
}
