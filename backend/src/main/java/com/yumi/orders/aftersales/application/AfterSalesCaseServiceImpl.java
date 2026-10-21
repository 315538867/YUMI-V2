package com.yumi.orders.aftersales.application;

import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesBearerType;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesCaseStatus;
import com.yumi.orders.aftersales.api.AfterSalesCaseView;
import com.yumi.orders.aftersales.api.AfterSalesCompletionRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesObligationStatus;
import com.yumi.orders.aftersales.api.AfterSalesObligationType;
import com.yumi.orders.aftersales.api.AfterSalesQuery;
import com.yumi.orders.aftersales.api.AfterSalesReturnDispositionService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.CompleteAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.DisposeReturnedProductCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReturnDispositionRef;
import com.yumi.orders.aftersales.application.port.AfterSalesRepository;
import com.yumi.orders.application.port.OrderRepository;
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
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 售后案例与义务实现（基线 12 §13.1、§13.2）。
 *
 * <p>只从已完成订单受理；案例建立问题事实并分别保存客户配合、数量与金额义务。
 * 结案要求全部有效义务已履行或作废，不覆盖原交易事实。
 */
public class AfterSalesCaseServiceImpl
        implements AfterSalesApplicationService, AfterSalesQuery {

    private static final String OPEN_SCOPE = "orders.aftersales.open";
    private static final String COMPLETE_SCOPE = "orders.aftersales.complete";

    private final AfterSalesRepository cases;
    private final AfterSalesReturnDispositionService dispositions;
    private final OrderRepository orders;
    private final IdempotentCommandService idempotency;
    private final BusinessNumberService numbering;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AfterSalesCaseServiceImpl(
            AfterSalesRepository cases,
            AfterSalesReturnDispositionService dispositions,
            OrderRepository orders,
            IdempotentCommandService idempotency,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.dispositions = dispositions;
        this.orders = orders;
        this.idempotency = idempotency;
        this.numbering = numbering;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AfterSalesCaseRef open(OpenAfterSalesCaseCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASE", "售后受理命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(OPEN_SCOPE, command.idempotencyKey(), openFingerprint(command)),
                () -> new CommandOutcome("AFTER_SALES_CASE", doOpen(command))).outcome());
        var record = cases.findCase(outcome.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_CASE_MISSING", "售后案例无法读取"));
        return new AfterSalesCaseRef(
                record.caseBusinessId(), record.caseNo(),
                AfterSalesCaseStatus.valueOf(record.status()), record.contentVersion());
    }

    @Override
    public ReturnDispositionRef disposeReturnedProduct(DisposeReturnedProductCommand command) {
        return dispositions.disposeReturnedProduct(command);
    }

    @Override
    public AfterSalesCompletionRef complete(CompleteAfterSalesCaseCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASE", "结案命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        COMPLETE_SCOPE, command.idempotencyKey(), sha256(command.caseBusinessId())),
                () -> new CommandOutcome(
                        "AFTER_SALES_CASE", doComplete(command.caseBusinessId()))).outcome());
        var completion = cases.findCompletion(outcome.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_COMPLETION_MISSING", "结案事实无法读取"));
        return new AfterSalesCompletionRef(
                outcome.resultBusinessId(), completion.occurredAt());
    }

    @Override
    public AfterSalesCaseView get(String caseBusinessId) {
        authorization.require(PermissionCodes.AFTER_SALES_READ, ResourceScope.GLOBAL);
        var record = cases.findCase(caseBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_CASE_NOT_FOUND", "售后案例不存在"));
        return toView(record);
    }

    @Override
    public java.util.List<AfterSalesCaseView> search(
            String keyword, AfterSalesCaseStatus status, int page, int size) {
        authorization.require(PermissionCodes.AFTER_SALES_READ, ResourceScope.GLOBAL);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return cases.searchCases(
                        keyword == null ? "" : keyword.strip(),
                        status == null ? null : status.name(),
                        safePage * safeSize, safeSize)
                .stream().map(this::toView).toList();
    }

    private AfterSalesCaseView toView(AfterSalesRepository.CaseRecord record) {
        var obligations = cases.findObligations(record.caseBusinessId());
        var completion = cases.findCompletion(record.caseBusinessId()).orElse(null);
        return new AfterSalesCaseView(
                record.caseBusinessId(), record.caseNo(), record.orderBusinessId(),
                record.customerBusinessId(), record.issueSummary(),
                AfterSalesRouteType.valueOf(record.routeType()),
                AfterSalesCaseStatus.valueOf(record.status()), record.openedAt(),
                record.contentVersion(),
                obligations.stream().map(row -> new AfterSalesCaseView.ObligationView(
                        row.obligationBusinessId(),
                        AfterSalesObligationType.valueOf(row.obligationType()),
                        AfterSalesBearerType.valueOf(row.bearerType()),
                        row.targetQuantity(),
                        row.targetAmount() == null ? null
                                : SettlementMoney.of(row.targetAmount(), CurrencyCode.CNY),
                        row.dueDate(), row.note(),
                        AfterSalesObligationStatus.valueOf(row.status()), row.rowVersion())).toList(),
                completion == null ? null : completion.occurredAt());
    }

    private String doOpen(OpenAfterSalesCaseCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        orders.lockOrder(command.orderBusinessId());
        var order = orders.findOrder(command.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"));
        if (!"COMPLETED".equals(order.status()) || orders.findCompletion(
                command.orderBusinessId()).isEmpty()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_ORDER_NOT_COMPLETED",
                    "只能为已完成订单受理售后");
        }

        String caseId = "ASC-" + UUID.randomUUID();
        String caseNo = numbering.nextNumber("AFTER_SALES_CASE", "AS", 6);
        cases.insertCase(new AfterSalesRepository.CaseRecord(
                caseId, caseNo, command.orderBusinessId(), order.customerBusinessId(),
                command.issueSummary().strip(), command.routeType().name(), "OPEN", now, now, 1L, 1L));
        cases.insertIssue(new AfterSalesRepository.IssueRecord(
                "AIF-" + UUID.randomUUID(), caseId, "CUSTOMER_FEEDBACK", null,
                command.issueSummary().strip(), 0, now));
        for (AfterSalesObligationRequest obligation : command.obligations()) {
            if (obligation.orderItemBusinessId() != null) {
                var item = orders.findItem(obligation.orderItemBusinessId())
                        .orElseThrow(() -> new ApiException(
                                ApiErrorType.NOT_FOUND, "ORDER_ITEM_NOT_FOUND", "订单项不存在"));
                if (!item.orderBusinessId().equals(command.orderBusinessId())) {
                    throw new ApiException(
                            ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_ORDER_MISMATCH",
                            "售后义务引用了其他订单的订单项");
                }
            }
            cases.insertObligation(new AfterSalesRepository.ObligationRecord(
                    "ASO-" + UUID.randomUUID(), caseId, obligation.orderItemBusinessId(),
                    obligation.obligationType().name(),
                    bearerOf(obligation.obligationType()).name(), null, null,
                    obligation.targetQuantity(),
                    obligation.targetAmount() == null ? null : obligation.targetAmount().value(),
                    0, BigDecimal.ZERO, obligation.dueDate(), obligation.note(), "PENDING", 1L));
        }

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders", "OPEN_AFTER_SALES_CASE",
                "AFTER_SALES_CASE", caseId, "SUCCESS",
                Map.of("caseNo", caseNo, "orderBusinessId", command.orderBusinessId()),
                now));
        return caseId;
    }

    private String doComplete(String caseBusinessId) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        var record = cases.lockCase(caseBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_CASE_NOT_FOUND", "售后案例不存在"));
        var existing = cases.findCompletion(caseBusinessId);
        if (existing.isPresent()) {
            return existing.get().factBusinessId();
        }
        var obligations = cases.lockObligations(caseBusinessId);
        List<String> unfinished = obligations.stream()
                .filter(row -> !"FULFILLED".equals(row.status()) && !"VOIDED".equals(row.status()))
                .map(row -> row.obligationBusinessId() + ":" + row.obligationType())
                .toList();
        if (!unfinished.isEmpty()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "AFTER_SALES_OBLIGATION_NOT_READY",
                    "存在未完成售后义务：" + String.join("，", unfinished));
        }

        String factId = "ACF-" + UUID.randomUUID();
        cases.insertCompletion(new AfterSalesRepository.CompletionRecord(
                factId, caseBusinessId, "COMPLETED", obligationFingerprint(caseBusinessId, obligations),
                now, operatorId));
        cases.markCaseCompleted(caseBusinessId);
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders",
                "COMPLETE_AFTER_SALES_CASE", "AFTER_SALES_CASE", caseBusinessId, "SUCCESS",
                Map.of("caseNo", record.caseNo()), now));
        return caseBusinessId;
    }

    private static AfterSalesBearerType bearerOf(AfterSalesObligationType type) {
        return type == AfterSalesObligationType.CUSTOMER_COOPERATION
                ? AfterSalesBearerType.CUSTOMER : AfterSalesBearerType.WORKSHOP;
    }

    private static String obligationFingerprint(
            String caseBusinessId, List<AfterSalesRepository.ObligationRecord> obligations) {
        StringBuilder builder = new StringBuilder(caseBusinessId);
        obligations.stream().sorted(java.util.Comparator.comparing(
                        AfterSalesRepository.ObligationRecord::obligationBusinessId))
                .forEach(row -> builder.append('|').append(row.obligationBusinessId())
                        .append(':').append(row.status()));
        return sha256(builder.toString());
    }

    private static String openFingerprint(OpenAfterSalesCaseCommand command) {
        StringBuilder builder = new StringBuilder(command.orderBusinessId())
                .append('|').append(command.issueSummary()).append('|').append(command.routeType());
        for (AfterSalesObligationRequest obligation : command.obligations()) {
            builder.append('|').append(obligation.obligationType())
                    .append(':').append(obligation.targetQuantity())
                    .append(':').append(obligation.targetAmount() == null
                            ? null : obligation.targetAmount().value().toPlainString())
                    .append(':').append(obligation.dueDate());
        }
        return sha256(builder.toString());
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
