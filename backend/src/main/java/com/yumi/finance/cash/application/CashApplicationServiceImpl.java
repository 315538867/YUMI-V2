package com.yumi.finance.cash.application;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.CashFactRef;
import com.yumi.finance.cash.api.DailyCashRecordRef;
import com.yumi.finance.cash.api.RecordDailyCashCommand;
import com.yumi.finance.cash.api.ReturnPayrollPaymentCommand;
import com.yumi.finance.cash.api.ReverseCashFactCommand;
import com.yumi.finance.cash.api.PayPayrollCommand;
import com.yumi.finance.cash.api.ReceiveOrderPaymentCommand;
import com.yumi.finance.cash.api.RefundAfterSalesPaymentCommand;
import com.yumi.finance.cash.api.RefundOrderPaymentCommand;
import com.yumi.orders.api.CommitOrderPaymentLink;
import com.yumi.orders.api.OrderPaymentParticipant;
import com.yumi.orders.api.OrderPaymentRequest;
import com.yumi.orders.api.OrderPaymentType;
import com.yumi.orders.api.ReverseOrderPaymentLink;
import com.yumi.orders.aftersales.api.AfterSalesPaymentParticipant;
import com.yumi.orders.aftersales.api.AfterSalesPaymentRequest;
import com.yumi.orders.aftersales.api.CommitAfterSalesPaymentLink;
import com.yumi.finance.payroll.api.CommitPayrollPaymentLink;
import com.yumi.finance.costledger.api.OperatingExpenseCommand;
import com.yumi.finance.costledger.api.OperatingExpenseService;
import com.yumi.finance.payroll.api.PayrollPaymentParticipant;
import com.yumi.finance.payroll.api.PayrollPaymentRequest;
import com.yumi.finance.payroll.api.PayrollReturnRequest;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.transaction.DeadlockRetryTemplate;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.file.AttachFileReference;
import com.yumi.platform.api.file.FileReferenceParticipant;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 真实现金命令（基线 13 §8.2、§8.3）。
 *
 * <p>现金事实、来源分配、业务参与关联和来源收付投影同事务提交；订单/售后/工资资格由对应
 * 模块在同一事务校验，cash 不复制业务规则。
 */
public class CashApplicationServiceImpl implements CashApplicationService {

    private static final String RECEIVE_SCOPE = "finance.cash.receive";
    private static final String REFUND_SCOPE = "finance.cash.refund";
    private static final String AFTER_SALES_SCOPE = "finance.cash.after_sales";
    private static final String PAYROLL_SCOPE = "finance.cash.payroll";

    private final CashRepository cash;
    private final OrderPaymentParticipant orderPayments;
    private final AfterSalesPaymentParticipant afterSalesPayments;
    private final PayrollPaymentParticipant payrollPayments;
    private final OperatingExpenseService operatingExpenses;
    private final FileReferenceParticipant fileReferences;
    private final BusinessNumberService numbering;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final DeadlockRetryTemplate retryTemplate = new DeadlockRetryTemplate();

    public CashApplicationServiceImpl(
            CashRepository cash,
            OrderPaymentParticipant orderPayments,
            AfterSalesPaymentParticipant afterSalesPayments,
            PayrollPaymentParticipant payrollPayments,
            OperatingExpenseService operatingExpenses,
            FileReferenceParticipant fileReferences,
            BusinessNumberService numbering,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.cash = cash;
        this.orderPayments = orderPayments;
        this.afterSalesPayments = afterSalesPayments;
        this.payrollPayments = payrollPayments;
        this.operatingExpenses = operatingExpenses;
        this.fileReferences = fileReferences;
        this.numbering = numbering;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public CashFactRef receiveOrder(ReceiveOrderPaymentCommand command) {
        authorization.require(PermissionCodes.CASH_RECEIVE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASH", "收款命令不能为空");
        }
        String cashId = execute(command.idempotencyKey(), RECEIVE_SCOPE,
                fingerprint("ORDER_RECEIPT", command.orderBusinessId(),
                        command.amount().value().toPlainString(), command.businessDate().toString()),
                () -> {
                    var prepared = orderPayments.preparePayment(new OrderPaymentRequest(
                            command.orderBusinessId(), OrderPaymentType.RECEIPT, command.amount()));
                    String id = newCashId();
                    insertFact(id, "IN", "ORDER_RECEIPT", command.businessDate(),
                            command.amount().value());
                    attachVouchers(id, command.voucherFileRefs());
                    cash.insertAllocation(new CashRepository.AllocationRecord(
                            "CAL-" + UUID.randomUUID(), id, "orders", "ORDER",
                            command.orderBusinessId(), command.amount().value()));
                    orderPayments.commitPaymentLink(new CommitOrderPaymentLink(
                            prepared, id));
                    applyProjection("orders", "ORDER", command.orderBusinessId(),
                            "ORDER_RECEIPT", command.amount().value());
                    return id;
                });
        return ref(cashId);
    }

    @Override
    public CashFactRef refundOrder(RefundOrderPaymentCommand command) {
        authorization.require(PermissionCodes.CASH_PAY, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASH", "退款命令不能为空");
        }
        String cashId = execute(command.idempotencyKey(), REFUND_SCOPE,
                fingerprint("ORDER_REFUND", command.orderBusinessId(),
                        command.amount().value().toPlainString(), command.businessDate().toString()),
                () -> {
                    var prepared = orderPayments.preparePayment(new OrderPaymentRequest(
                            command.orderBusinessId(), OrderPaymentType.REFUND, command.amount()));
                    String id = newCashId();
                    insertFact(id, "OUT", "ORDER_REFUND", command.businessDate(),
                            command.amount().value());
                    attachVouchers(id, command.voucherFileRefs());
                    cash.insertAllocation(new CashRepository.AllocationRecord(
                            "CAL-" + UUID.randomUUID(), id, "orders", "ORDER",
                            command.orderBusinessId(), command.amount().value()));
                    orderPayments.commitPaymentLink(new CommitOrderPaymentLink(prepared, id));
                    applyProjection("orders", "ORDER", command.orderBusinessId(),
                            "ORDER_REFUND", command.amount().value());
                    return id;
                });
        return ref(cashId);
    }

    @Override
    public CashFactRef refundAfterSales(RefundAfterSalesPaymentCommand command) {
        authorization.require(PermissionCodes.CASH_PAY, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASH", "售后支付命令不能为空");
        }
        String cashId = execute(command.idempotencyKey(), AFTER_SALES_SCOPE,
                fingerprint("AFTER_SALES", command.obligationBusinessId(),
                        command.amount().value().toPlainString(), command.businessDate().toString()),
                () -> {
                    var prepared = afterSalesPayments.preparePayment(
                            new AfterSalesPaymentRequest(
                                    command.obligationBusinessId(), command.amount()));
                    String id = newCashId();
                    insertFact(id, "OUT", "AFTER_SALES_REFUND", command.businessDate(),
                            command.amount().value());
                    attachVouchers(id, command.voucherFileRefs());
                    cash.insertAllocation(new CashRepository.AllocationRecord(
                            "CAL-" + UUID.randomUUID(), id, "orders", "AFTER_SALES_OBLIGATION",
                            command.obligationBusinessId(), command.amount().value()));
                    afterSalesPayments.commitPaymentLink(
                            new CommitAfterSalesPaymentLink(prepared, id));
                    applyProjection("orders", "AFTER_SALES_OBLIGATION",
                            command.obligationBusinessId(), "AFTER_SALES_REFUND",
                            command.amount().value());
                    return id;
                });
        return ref(cashId);
    }

    @Override
    public CashFactRef payPayroll(PayPayrollCommand command) {
        authorization.require(PermissionCodes.CASH_PAY, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASH", "工资付款命令不能为空");
        }
        BigDecimal total = command.allocations().stream()
                .map(allocation -> allocation.amount().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String cashId = execute(command.idempotencyKey(), PAYROLL_SCOPE,
                fingerprint("PAYROLL_PAYMENT", command.businessDate().toString(),
                        command.allocations().stream()
                                .sorted(Comparator.comparing(PayPayrollCommand.PayrollAllocation::resultBusinessId))
                                .map(allocation -> allocation.resultBusinessId() + ":"
                                        + allocation.amount().value().toPlainString())
                                .reduce("", (left, right) -> left + "|" + right),
                        total.toPlainString()),
                () -> {
                    var sorted = command.allocations().stream()
                            .sorted(Comparator.comparing(
                                    PayPayrollCommand.PayrollAllocation::resultBusinessId))
                            .toList();
                    java.util.List<com.yumi.finance.payroll.api.PreparedPayrollPayment> prepared = sorted.stream()
                            .map(allocation -> payrollPayments.preparePayment(
                                    new PayrollPaymentRequest(
                                            allocation.resultBusinessId(), allocation.amount(),
                                            "PENDING")))
                            .toList();
                    String id = newCashId();
                    insertFact(id, "OUT", "PAYROLL_PAYMENT", command.businessDate(), total);
                    attachVouchers(id, command.voucherFileRefs());
                    for (int index = 0; index < sorted.size(); index++) {
                        var allocation = sorted.get(index);
                        var preparedPayment = prepared.get(index);
                        cash.insertAllocation(new CashRepository.AllocationRecord(
                                "CAL-" + UUID.randomUUID(), id, "finance", "PAYROLL_RESULT",
                                allocation.resultBusinessId(), allocation.amount().value()));
                        payrollPayments.commitPaymentLink(new CommitPayrollPaymentLink(
                                preparedPayment, id));
                        applyProjection("finance", "PAYROLL_RESULT",
                                allocation.resultBusinessId(), "PAYROLL_PAYMENT",
                                allocation.amount().value());
                    }
                    return id;
                });
        return ref(cashId);
    }

    @Override
    public DailyCashRecordRef recordDailyCash(RecordDailyCashCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASH", "日常收支命令不能为空");
        }
        authorization.require(
                "IN".equals(command.direction())
                        ? PermissionCodes.CASH_RECEIVE : PermissionCodes.CASH_PAY,
                ResourceScope.GLOBAL);
        String dailyId = execute(command.idempotencyKey(), "finance.cash.daily",
                fingerprint("DAILY", command.direction(), command.categoryCode(),
                        command.businessDate().toString(),
                        command.amount().value().toPlainString(),
                        String.valueOf(command.countAsExpense())),
                () -> {
                    String id = newCashId();
                    String type = "IN".equals(command.direction())
                            ? "DAILY_INCOME" : "DAILY_EXPENSE";
                    insertFact(id, command.direction(), type, command.businessDate(),
                            command.amount().value());
                    attachVouchers(id, command.voucherFileRefs());
                    String daily = "DCR-" + UUID.randomUUID();
                    cash.insertDailyDetail(new CashRepository.DailyDetailRecord(
                            daily, id, command.categoryCode(), command.description().strip(),
                            command.countAsExpense(), null, clock.instant()));
                    if (command.countAsExpense()) {
                        var expense = operatingExpenses.record(new OperatingExpenseCommand(
                                daily, id,
                                com.yumi.platform.api.value.CostAmount.of(
                                        command.amount().value(), CurrencyCode.CNY),
                                command.businessDate(), command.categoryCode()));
                        cash.updateDailyCostEntry(daily, expense.costEntryBusinessId());
                    }
                    applyProjection("finance", "DAILY_RECORD", daily, type,
                            command.amount().value());
                    return daily;
                });
        var detail = cash.findDailyDetail(dailyId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "DAILY_CASH_MISSING", "日常收支记录无法读取"));
        return new DailyCashRecordRef(
                dailyId, ref(detail.cashFactBusinessId()), detail.costEntryBusinessId());
    }

    @Override
    public CashFactRef returnPayroll(ReturnPayrollPaymentCommand command) {
        authorization.require(PermissionCodes.CASH_PAYROLL_RETURN, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASH", "工资退回命令不能为空");
        }
        String cashId = execute(command.idempotencyKey(), "finance.cash.payroll_return",
                fingerprint("PAYROLL_RETURN", command.originalCashFactBusinessId(),
                        command.resultBusinessId(), command.amount().value().toPlainString(),
                        command.businessDate().toString()),
                () -> {
                    var original = cash.findCashFact(command.originalCashFactBusinessId())
                            .orElseThrow(() -> new ApiException(
                                    ApiErrorType.NOT_FOUND, "CASH_FACT_NOT_FOUND",
                                    "原工资付款现金事实不存在"));
                    if (!"PAYROLL_PAYMENT".equals(original.cashType())
                            || !"OUT".equals(original.direction())) {
                        throw new ApiException(
                                ApiErrorType.BUSINESS_REJECTION, "PAYROLL_RETURN_SOURCE_INVALID",
                                "工资退回必须引用原工资付款");
                    }
                    var allocation = cash.findAllocation(
                                    command.originalCashFactBusinessId(), "PAYROLL_RESULT",
                                    command.resultBusinessId())
                            .orElseThrow(() -> new ApiException(
                                    ApiErrorType.BUSINESS_REJECTION,
                                    "PAYROLL_RETURN_ALLOCATION_NOT_FOUND",
                                    "原付款中没有该员工的分配"));
                    LocalDate today = LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
                    if (command.businessDate().isBefore(original.businessDate())
                            || command.businessDate().isAfter(today)) {
                        throw new ApiException(
                                ApiErrorType.VALIDATION, "PAYROLL_RETURN_DATE_INVALID",
                                "退回业务日期不得早于原付款日期或晚于当天");
                    }
                    BigDecimal returned = cash.returnedAmount(
                            command.originalCashFactBusinessId(), command.resultBusinessId());
                    BigDecimal remaining = allocation.amount().subtract(returned);
                    if (command.amount().value().compareTo(remaining) > 0) {
                        throw new ApiException(
                                ApiErrorType.BUSINESS_REJECTION,
                                "PAYROLL_RETURN_EXCEEDS_ALLOCATION",
                                "退回超过原员工分配可退余额：可退 "
                                        + remaining.setScale(2).toPlainString());
                    }
                    var prepared = payrollPayments.prepareReturn(new PayrollReturnRequest(
                            command.resultBusinessId(), command.originalCashFactBusinessId(),
                            command.amount()));
                    String id = newCashId();
                    insertFact(id, "IN", "PAYROLL_RETURN", command.businessDate(),
                            command.amount().value());
                    attachVouchers(id, command.voucherFileRefs());
                    cash.insertAllocation(new CashRepository.AllocationRecord(
                            "CAL-" + UUID.randomUUID(), id, "finance", "PAYROLL_RESULT",
                            command.resultBusinessId(), command.amount().value()));
                    cash.insertPayrollReturnDetail(new CashRepository.PayrollReturnDetailRecord(
                            id, command.originalCashFactBusinessId(),
                            allocation.allocationBusinessId(), command.resultBusinessId(),
                            command.amount().value(), command.reasonText().strip(), "ACCRUE",
                            null));
                    payrollPayments.commitReturn(new PayrollReturnRequest(
                            command.resultBusinessId(), command.originalCashFactBusinessId(),
                            command.amount()), id);
                    applyProjection("finance", "PAYROLL_RESULT", command.resultBusinessId(),
                            "PAYROLL_RETURN", command.amount().value());
                    return id;
                });
        return ref(cashId);
    }

    @Override
    public CashFactRef reverse(ReverseCashFactCommand command) {
        authorization.require(PermissionCodes.CASH_REVERSE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASH", "现金冲销命令不能为空");
        }
        String reversalId = execute(command.idempotencyKey(), "finance.cash.reverse",
                fingerprint("REVERSAL", command.cashFactBusinessId(),
                        command.businessDate().toString()),
                () -> {
                    var original = cash.lockCashFact(command.cashFactBusinessId())
                            .orElseThrow(() -> new ApiException(
                                    ApiErrorType.NOT_FOUND, "CASH_FACT_NOT_FOUND", "现金事实不存在"));
                    if ("REVERSAL".equals(original.cashType())) {
                        throw new ApiException(
                                ApiErrorType.BUSINESS_REJECTION, "CASH_REVERSAL_INVALID",
                                "反向现金事实不能再被反向");
                    }
                    if (cash.findReversalOf(original.cashFactBusinessId()).isPresent()) {
                        throw new ApiException(
                                ApiErrorType.BUSINESS_REJECTION, "CASH_ALREADY_REVERSED",
                                "该现金事实已经冲销");
                    }
                    String id = newCashId();
                    String direction = "IN".equals(original.direction()) ? "OUT" : "IN";
                    cash.insertCashFact(new CashRepository.CashFactRecord(
                            id, numbering.nextNumber("CASH", "CSH", 6), direction, "REVERSAL",
                            command.businessDate(), original.amount(), "CNY", clock.instant(),
                            currentActor.requireCurrentActor().accountBusinessId().value(),
                            original.cashFactBusinessId()));
                    attachVouchers(id, command.voucherFileRefs());
                    reverseBusinessLink(original, id, command.reasonText());
                    for (var allocation : cash.findAllocations(original.cashFactBusinessId())) {
                        cash.insertAllocation(new CashRepository.AllocationRecord(
                                "CAL-" + UUID.randomUUID(), id, allocation.sourceModule(),
                                allocation.sourceType(), allocation.sourceBusinessId(),
                                allocation.amount()));
                        reverseProjection(allocation, original.cashType());
                    }
                    return id;
                });
        return ref(reversalId);
    }

    private void reverseBusinessLink(
            CashRepository.CashFactRecord original, String reversalCashId, String reason) {
        switch (original.cashType()) {
            case "ORDER_RECEIPT", "ORDER_REFUND" -> {
                String linkId = orderPayments.findPaymentLink(original.cashFactBusinessId());
                orderPayments.reversePaymentLink(
                        new ReverseOrderPaymentLink(linkId, reversalCashId, reason.strip()));
            }
            case "AFTER_SALES_REFUND", "AFTER_SALES_COMPENSATION" -> {
                String linkId = afterSalesPayments.findPaymentLink(original.cashFactBusinessId());
                afterSalesPayments.reversePaymentLink(
                        new com.yumi.orders.aftersales.api.ReverseAfterSalesPaymentLink(
                                linkId, reversalCashId, reason.strip()));
            }
            case "PAYROLL_PAYMENT" -> {
                String linkId = payrollPayments.findPaymentLink(original.cashFactBusinessId());
                payrollPayments.reversePaymentLink(new com.yumi.finance.payroll.api.ReversePayrollPaymentLink(
                        linkId, reversalCashId, reason.strip()));
            }
            case "PAYROLL_RETURN" -> {
                var detail = cash.findPayrollReturnDetail(original.cashFactBusinessId())
                        .orElseThrow(() -> new ApiException(
                                ApiErrorType.SYSTEM_FAILURE, "PAYROLL_RETURN_DETAIL_MISSING",
                                "工资退回明细不存在"));
                cash.insertPayrollReturnDetail(new CashRepository.PayrollReturnDetailRecord(
                        reversalCashId, detail.originalPaymentCashFactBusinessId(),
                        detail.originalPaymentAllocationBusinessId(), detail.resultBusinessId(),
                        detail.amount(), reason.strip(), "REVERSE",
                        detail.returnCashFactBusinessId()));
                payrollPayments.reverseReturn(original.cashFactBusinessId(), reversalCashId);
            }
            case "DAILY_INCOME", "DAILY_EXPENSE" -> cash
                    .findDailyDetailByCashFact(original.cashFactBusinessId())
                    .filter(detail -> detail.costEntryBusinessId() != null)
                    .ifPresent(detail -> operatingExpenses.reverse(
                            detail.dailyRecordBusinessId(), reason.strip()));
            default -> throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "CASH_TYPE_UNSUPPORTED", "不支持的现金冲销类型");
        }
    }

    private void reverseProjection(
            CashRepository.AllocationRecord allocation, String cashType) {
        var current = cash.projectionOf(
                allocation.sourceModule(), allocation.sourceType(), allocation.sourceBusinessId());
        BigDecimal received = current.receivedAmount();
        BigDecimal paid = current.paidAmount();
        BigDecimal refunded = current.refundedAmount();
        BigDecimal amount = allocation.amount();
        switch (cashType) {
            case "ORDER_RECEIPT", "DAILY_INCOME", "PAYROLL_RETURN" ->
                    received = received.subtract(amount);
            case "ORDER_REFUND" -> refunded = refunded.subtract(amount);
            case "AFTER_SALES_REFUND", "AFTER_SALES_COMPENSATION", "PAYROLL_PAYMENT",
                    "DAILY_EXPENSE" -> paid = paid.subtract(amount);
            default -> throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "CASH_TYPE_UNSUPPORTED", "不支持的现金冲销类型");
        }
        if (received.signum() < 0 || paid.signum() < 0 || refunded.signum() < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "CASH_PROJECTION_INCONSISTENT",
                    "冲销导致来源收付投影为负");
        }
        cash.upsertProjection(new CashRepository.ProjectionRecord(
                allocation.sourceModule(), allocation.sourceType(),
                allocation.sourceBusinessId(), received, paid, refunded));
    }

    private String execute(
            String idempotencyKey, String scope, String fingerprint,
            java.util.function.Supplier<String> body) {
        CommandOutcome outcome = retryTemplate.execute(
                () -> transactionTemplate.execute(status -> idempotency.execute(
                        new CommandRequest(scope, idempotencyKey, fingerprint),
                        () -> new CommandOutcome("CASH_FACT", body.get())).outcome()),
                failure -> failure instanceof org.springframework.dao.PessimisticLockingFailureException);
        return outcome.resultBusinessId();
    }

    private String newCashId() {
        return "CSH-" + UUID.randomUUID();
    }

    private void insertFact(
            String id, String direction, String type, java.time.LocalDate businessDate,
            BigDecimal amount) {
        String no = numbering.nextNumber("CASH", "CSH", 6);
        cash.insertCashFact(new CashRepository.CashFactRecord(
                id, no, direction, type, businessDate, amount, "CNY", clock.instant(),
                currentActor.requireCurrentActor().accountBusinessId().value(), null));
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(),
                currentActor.requireCurrentActor().accountBusinessId().value(),
                "finance", "RECORD_CASH", "CASH_FACT", id, "SUCCESS",
                Map.of("cashNo", no, "direction", direction, "type", type,
                        "amount", amount.toPlainString()),
                clock.instant()));
    }

    private void applyProjection(
            String module, String type, String sourceId, String cashType, BigDecimal amount) {
        var current = cash.projectionOf(module, type, sourceId);
        BigDecimal received = current.receivedAmount();
        BigDecimal paid = current.paidAmount();
        BigDecimal refunded = current.refundedAmount();
        switch (cashType) {
            case "ORDER_RECEIPT" -> received = received.add(amount);
            case "ORDER_REFUND" -> refunded = refunded.add(amount);
            case "AFTER_SALES_REFUND", "AFTER_SALES_COMPENSATION", "PAYROLL_PAYMENT",
                    "DAILY_EXPENSE" -> paid = paid.add(amount);
            case "DAILY_INCOME", "PAYROLL_RETURN" -> received = received.add(amount);
            default -> throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "CASH_TYPE_UNSUPPORTED", "不支持的现金类型");
        }
        cash.upsertProjection(new CashRepository.ProjectionRecord(
                module, type, sourceId, received, paid, refunded));
    }

    private void attachVouchers(String cashFactId, List<String> fileRefs) {
        if (fileRefs == null || fileRefs.isEmpty()) {
            return;
        }
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        for (String fileId : new java.util.LinkedHashSet<>(fileRefs)) {
            fileReferences.attach(new AttachFileReference(
                    fileId, "finance", "CASH_FACT", cashFactId, "CASH_VOUCHER"));
            cash.insertVoucherLink(new CashRepository.VoucherLinkRecord(
                    "CVL-" + UUID.randomUUID(), cashFactId, fileId, "CASH_VOUCHER",
                    clock.instant(), operatorId));
        }
    }

    private CashFactRef ref(String cashId) {
        var fact = cash.findCashFact(cashId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "CASH_FACT_MISSING", "现金事实无法读取"));
        return new CashFactRef(
                fact.cashFactBusinessId(), fact.cashNo(), fact.direction(), fact.cashType(),
                SettlementMoney.of(fact.amount(), CurrencyCode.CNY), fact.businessDate());
    }

    private static String fingerprint(String... parts) {
        return sha256(String.join("|", parts));
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
