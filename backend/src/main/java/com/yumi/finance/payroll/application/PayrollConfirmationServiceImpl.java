package com.yumi.finance.payroll.application;

import com.yumi.finance.payroll.api.ConfirmEmployeePayrollCommand;
import com.yumi.finance.payroll.api.PayrollConfirmationRef;
import com.yumi.finance.payroll.api.PayrollConfirmationService;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.RequestContext;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 逐人工资核定实现（基线 13 §8.1）。
 *
 * <p>负责人明确填写的最终工资形成应付和实际人工成本；人工处理明细只保存，不自动抵扣。
 * 核定、应付与内部成本同事务提交。
 */
public class PayrollConfirmationServiceImpl implements PayrollConfirmationService {

    private static final String SCOPE = "finance.payroll.confirm_employee";

    private final PayrollRepository payroll;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PayrollConfirmationServiceImpl(
            PayrollRepository payroll,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.payroll = payroll;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public PayrollConfirmationRef confirmEmployee(ConfirmEmployeePayrollCommand command) {
        authorization.require(PermissionCodes.PAYROLL_CONFIRM, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CONFIRMATION", "工资核定命令不能为空");
        }
        idempotencyOutcome(command);
        var result = payroll.findResultById(command.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        var confirmation = payroll.findConfirmationByResult(result.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PAYROLL_CONFIRMATION_MISSING", "工资核定结果不存在"));
        return new PayrollConfirmationRef(
                result.resultBusinessId(), confirmation.confirmationBusinessId(),
                SettlementMoney.of(confirmation.confirmedAmount(), CurrencyCode.CNY),
                SettlementMoney.of(confirmation.confirmedAmount(), CurrencyCode.CNY));
    }

    private void idempotencyOutcome(ConfirmEmployeePayrollCommand command) {
        transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        SCOPE, command.idempotencyKey(),
                        fingerprint(command)),
                () -> new CommandOutcome(
                        "PAYROLL_RESULT", doConfirm(command))));
    }

    private String doConfirm(ConfirmEmployeePayrollCommand command) {
        var result = payroll.findResultById(command.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        if (!"REFERENCE_READY".equals(result.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_NOT_READY",
                    "只有待核定参考的员工可以确认工资");
        }
        if (result.rowVersion() != command.expectedRowVersion()) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "PAYROLL_VERSION_CONFLICT",
                    "工资结果已被其他操作修改，请刷新后重试");
        }
        if (!java.util.Objects.equals(
                result.referenceFingerprint(), command.referenceFingerprint())) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "PAYROLL_REFERENCE_STALE",
                    "工资参考已更新，请重新核对后再确认");
        }
        var period = payroll.findPeriod(result.periodBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PAYROLL_PERIOD_NOT_FOUND", "工资期间不存在"));
        var referenceLines = payroll.listReferenceLines(result.referenceRunBusinessId());
        var lineIds = referenceLines.stream()
                .map(PayrollRepository.ReferenceLineRecord::lineBusinessId)
                .collect(java.util.stream.Collectors.toSet());
        for (var item : command.processingItems()) {
            if (!lineIds.contains(item.referenceLineBusinessId())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PAYROLL_REFERENCE_LINE_INVALID",
                        "人工处理明细引用了其他员工或过期的参考行");
            }
        }

        String confirmationId = "PYC-" + UUID.randomUUID();
        var now = clock.instant();
        LocalDate businessDate = LocalDate.ofInstant(
                now, ZoneId.of(RequestContext.DEFAULT_BUSINESS_ZONE));
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        payroll.insertConfirmation(new PayrollRepository.ConfirmationRecord(
                confirmationId, result.resultBusinessId(), command.confirmedAmount().value(),
                result.referenceRunBusinessId(), result.referenceFingerprint(),
                command.note().strip(), now, operatorId));
        for (var item : command.processingItems()) {
            payroll.insertProcessingItem(new PayrollRepository.ProcessingItemRecord(
                    "PYI-" + UUID.randomUUID(), result.resultBusinessId(), confirmationId,
                    item.referenceLineBusinessId(),
                    item.manualAmount() == null ? null : item.manualAmount().value(),
                    item.note(), now));
        }
        payroll.confirmResult(
                result.resultBusinessId(), command.confirmedAmount().value(),
                command.expectedRowVersion());

        if (command.confirmedAmount().value().signum() > 0) {
            BigDecimal costAmount = command.confirmedAmount().value()
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            payroll.insertDirectLaborCost(new PayrollRepository.DirectLaborCostRecord(
                    "DLC-" + UUID.randomUUID(), result.resultBusinessId(), costAmount,
                    businessDate, now));
        }

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "finance",
                "CONFIRM_EMPLOYEE_PAYROLL", "PAYROLL_RESULT", result.resultBusinessId(), "SUCCESS",
                Map.of("confirmationId", confirmationId,
                        "confirmedAmount", command.confirmedAmount().value().toPlainString(),
                        "processingItems", String.valueOf(command.processingItems().size())),
                now));
        return result.resultBusinessId();
    }

    private static String fingerprint(ConfirmEmployeePayrollCommand command) {
        StringBuilder builder = new StringBuilder(command.resultBusinessId())
                .append('|').append(command.expectedRowVersion())
                .append('|').append(command.referenceFingerprint())
                .append('|').append(command.confirmedAmount().value().toPlainString());
        command.processingItems().forEach(item -> builder
                .append('|').append(item.referenceLineBusinessId())
                .append(':').append(item.manualAmount() == null
                        ? null : item.manualAmount().value().toPlainString()));
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
