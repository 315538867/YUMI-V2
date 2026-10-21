package com.yumi.orders.aftersales.application;

import com.yumi.orders.aftersales.api.AfterSalesPaymentLinkRef;
import com.yumi.orders.aftersales.api.AfterSalesPaymentParticipant;
import com.yumi.orders.aftersales.api.AfterSalesPaymentRequest;
import com.yumi.orders.aftersales.api.CommitAfterSalesPaymentLink;
import com.yumi.orders.aftersales.api.PreparedAfterSalesPayment;
import com.yumi.orders.aftersales.api.ReverseAfterSalesPaymentLink;
import com.yumi.orders.aftersales.application.port.AfterSalesRepository;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 售后金额义务支付参与实现。 */
public class AfterSalesPaymentParticipantImpl implements AfterSalesPaymentParticipant {

    private final AfterSalesRepository cases;
    private final CurrentActorProvider currentActor;
    private final Clock clock;

    public AfterSalesPaymentParticipantImpl(
            AfterSalesRepository cases, CurrentActorProvider currentActor, Clock clock) {
        this.cases = cases;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedAfterSalesPayment preparePayment(AfterSalesPaymentRequest request) {
        var obligation = validate(request);
        return new PreparedAfterSalesPayment(
                obligation.obligationBusinessId(), request.amount(),
                SettlementMoney.of(obligation.fulfilledAmount(), CurrencyCode.CNY),
                SettlementMoney.of(obligation.targetAmount(), CurrencyCode.CNY));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AfterSalesPaymentLinkRef commitPaymentLink(CommitAfterSalesPaymentLink command) {
        PreparedAfterSalesPayment prepared = command.prepared();
        var obligation = validate(new AfterSalesPaymentRequest(
                prepared.obligationBusinessId(), prepared.amount()));
        String factId = "ASP-" + UUID.randomUUID();
        cases.insertPaymentLink(new AfterSalesRepository.AfterSalesPaymentLinkRecord(
                factId, obligation.obligationBusinessId(),
                command.financeCashFactBusinessId(), prepared.amount().value(), null,
                clock.instant(), operatorId()));
        BigDecimal fulfilled = obligation.fulfilledAmount().add(prepared.amount().value());
        cases.updateObligationProgress(
                obligation.obligationBusinessId(), obligation.fulfilledQuantity(), fulfilled,
                fulfilled.compareTo(obligation.targetAmount()) >= 0 ? "FULFILLED" : "IN_PROGRESS",
                obligation.rowVersion());
        return new AfterSalesPaymentLinkRef(
                factId, obligation.obligationBusinessId(), prepared.amount(),
                SettlementMoney.of(fulfilled, CurrencyCode.CNY), false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AfterSalesPaymentLinkRef reversePaymentLink(ReverseAfterSalesPaymentLink command) {
        var original = cases.findPaymentLink(command.factBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_PAYMENT_NOT_FOUND", "售后支付关联不存在"));
        if (original.correctsFactBusinessId() != null) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_PAYMENT_REVERSAL_INVALID",
                    "反向售后支付不能再被反向");
        }
        if (cases.findPaymentLinkReversal(original.factBusinessId()).isPresent()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_PAYMENT_ALREADY_REVERSED",
                    "该售后支付已经反向，不能重复冲销");
        }
        var obligation = cases.lockObligation(original.obligationBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_OBLIGATION_NOT_FOUND", "售后义务不存在"));
        String factId = "ASP-" + UUID.randomUUID();
        cases.insertPaymentLink(new AfterSalesRepository.AfterSalesPaymentLinkRecord(
                factId, original.obligationBusinessId(), command.financeCashFactBusinessId(),
                original.linkedAmount(), original.factBusinessId(), clock.instant(),
                operatorId()));
        BigDecimal fulfilled = obligation.fulfilledAmount().subtract(original.linkedAmount())
                .max(BigDecimal.ZERO);
        cases.updateObligationProgress(
                obligation.obligationBusinessId(), obligation.fulfilledQuantity(), fulfilled,
                fulfilled.signum() == 0 ? "PENDING" : "IN_PROGRESS", obligation.rowVersion());
        return new AfterSalesPaymentLinkRef(
                factId, obligation.obligationBusinessId(),
                SettlementMoney.of(original.linkedAmount(), CurrencyCode.CNY),
                SettlementMoney.of(fulfilled, CurrencyCode.CNY), true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String findPaymentLink(String financeCashFactBusinessId) {
        return cases.findPaymentLinkByCashFact(financeCashFactBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_PAYMENT_NOT_FOUND", "售后支付关联不存在"))
                .factBusinessId();
    }

    private AfterSalesRepository.ObligationRecord validate(AfterSalesPaymentRequest request) {
        if (request == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "MISSING_AFTER_SALES_PAYMENT", "售后支付命令不能为空");
        }
        var obligation = cases.lockObligation(request.obligationBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_OBLIGATION_NOT_FOUND", "售后义务不存在"));
        if (!"REFUND".equals(obligation.obligationType())
                && !"COMPENSATION".equals(obligation.obligationType())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_PAYMENT_NOT_ALLOWED",
                    "只有退款或赔付金额义务可以支付");
        }
        if (obligation.targetAmount() == null) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_PAYMENT_NOT_ALLOWED",
                    "售后义务缺少目标金额");
        }
        BigDecimal remaining = obligation.targetAmount().subtract(obligation.fulfilledAmount());
        if (remaining.signum() <= 0 || request.amount().value().compareTo(remaining) > 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_AMOUNT_NOT_SETTLED",
                    "支付超过售后金额义务剩余额度：可付 " + remaining.setScale(2).toPlainString());
        }
        return obligation;
    }

    private String operatorId() {
        return currentActor.requireCurrentActor().accountBusinessId().value();
    }
}
