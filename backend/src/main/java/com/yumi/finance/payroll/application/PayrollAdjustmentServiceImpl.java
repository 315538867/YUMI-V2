package com.yumi.finance.payroll.application;

import com.yumi.finance.payroll.api.AdjustPayrollCommand;
import com.yumi.finance.payroll.api.PayrollAdjustmentRef;
import com.yumi.finance.payroll.api.PayrollAdjustmentService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
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
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已核定工资人工差额调整（基线 13 §5.1.4）。
 *
 * <p>只改变应付与人工成本差额，不产生现金；原核定与处理明细保持不变。
 */
public class PayrollAdjustmentServiceImpl implements PayrollAdjustmentService {

    private static final String SCOPE = "finance.payroll.adjust";

    private final PayrollRepository payroll;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PayrollAdjustmentServiceImpl(
            PayrollRepository payroll,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.payroll = payroll;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public PayrollAdjustmentRef adjust(AdjustPayrollCommand command) {
        authorization.require(PermissionCodes.PAYROLL_ADJUST, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_ADJUSTMENT", "工资调整命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        SCOPE, command.idempotencyKey(), fingerprint(command)),
                () -> new CommandOutcome("PAYROLL_ADJUSTMENT", doAdjust(command))).outcome());
        var result = payroll.findResultById(command.resultBusinessId()).orElseThrow();
        return new PayrollAdjustmentRef(
                result.resultBusinessId(), outcome.resultBusinessId(),
                SettlementMoney.of(result.payableAmount(), CurrencyCode.CNY));
    }

    private String doAdjust(AdjustPayrollCommand command) {
        var result = payroll.findResultById(command.resultBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        if (!"CONFIRMED".equals(result.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_NOT_CONFIRMED",
                    "只有已核定员工可以调整工资");
        }
        if (command.referenceRunBusinessId() != null) {
            var run = payroll.findRun(command.referenceRunBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.CONCURRENT_CONFLICT, "PAYROLL_REFERENCE_STALE",
                            "调整引用的参考运行不存在或已过期"));
            if (!run.employeeBusinessId().equals(result.employeeBusinessId())
                    || !run.periodBusinessId().equals(result.periodBusinessId())) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "PAYROLL_REFERENCE_STALE",
                        "调整引用的参考不属于该员工或期间");
            }
        }

        BigDecimal payable = result.payableAmount().add(command.amountDelta().value());
        if (payable.signum() < 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_ADJUSTMENT_BELOW_ZERO",
                    "调整后应付不得小于零");
        }
        String adjustmentId = "PYA-" + UUID.randomUUID();
        var now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        payroll.insertAdjustment(new PayrollRepository.AdjustmentRecord(
                adjustmentId, result.resultBusinessId(), command.amountDelta().value(),
                command.referenceRunBusinessId(), command.reasonText().strip(), now, operatorId));
        payroll.updatePayableAmount(
                result.resultBusinessId(), payable, result.rowVersion());
        payroll.insertCostAdjustment(new PayrollRepository.CostAdjustmentRecord(
                adjustmentId, result.resultBusinessId(),
                command.amountDelta().value().setScale(4, java.math.RoundingMode.HALF_UP), now));
        return adjustmentId;
    }

    private static String fingerprint(AdjustPayrollCommand command) {
        return sha256(command.resultBusinessId() + "|"
                + command.amountDelta().value().toPlainString() + "|"
                + command.reasonText() + "|" + command.referenceRunBusinessId());
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
