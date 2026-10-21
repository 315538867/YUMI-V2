package com.yumi.finance.payroll.application;

import com.yumi.calculation.api.MonthlyGlueAllowanceCalculator;
import com.yumi.calculation.api.MonthlyGlueAllowanceInput;
import com.yumi.calculation.api.MonthlyGlueAllowanceResult;
import com.yumi.finance.payroll.api.GlueAllowancePolicyRef;
import com.yumi.finance.payroll.api.GlueAllowancePolicyView;
import com.yumi.finance.payroll.api.GlueMonthReferenceView;
import com.yumi.finance.payroll.api.PayrollGlueAllowancePolicyService;
import com.yumi.finance.payroll.api.PayrollGlueAllowanceQuery;
import com.yumi.finance.payroll.api.PublishGlueAllowancePolicyCommand;
import com.yumi.finance.payroll.api.RunGlueMonthReferenceCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.WeightGram;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 胶水自然月设置与减免参考实现（基线 13 §5.1.3）。
 *
 * <p>每次月参考都是完整有效集合上的纯重算，不维护余额账户；已核定工资不被覆盖。
 */
public class PayrollGlueAllowanceServiceImpl
        implements PayrollGlueAllowancePolicyService, PayrollGlueAllowanceQuery {

    private static final String POLICY_SCOPE = "finance.payroll.publish_glue_policy";
    private static final String RUN_SCOPE = "finance.payroll.run_glue_month";
    private static final MonthlyGlueAllowanceCalculator CALCULATOR =
            MonthlyGlueAllowanceCalculator.create();

    private final PayrollGlueRepository glue;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PayrollGlueAllowanceServiceImpl(
            PayrollGlueRepository glue,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.glue = glue;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public GlueAllowancePolicyRef publish(PublishGlueAllowancePolicyCommand command) {
        authorization.require(
                PermissionCodes.PAYROLL_GLUE_ALLOWANCE_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_GLUE_POLICY", "胶水设置命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        POLICY_SCOPE, command.idempotencyKey(),
                        sha256(command.effectiveMonth() + "|"
                                + command.exemptWeightGrams().value().toPlainString() + "|"
                                + command.expectedGuardVersion())),
                () -> new CommandOutcome("PAYROLL_GLUE_POLICY", doPublish(command))).outcome());
        var policy = glue.findPolicyById(outcome.resultBusinessId()).orElseThrow();
        long guardVersion = glue.readGuardVersion();
        return new GlueAllowancePolicyRef(
                policy.policyBusinessId(), policy.effectiveMonth(), policy.version(),
                WeightGram.ofGrams(policy.exemptWeightGrams()), guardVersion);
    }

    @Override
    public GlueAllowancePolicyView getPolicy(LocalDate monthStart) {
        authorization.require(PermissionCodes.PAYROLL_GLUE_ALLOWANCE_READ, ResourceScope.GLOBAL);
        return glue.findPolicyForMonth(monthStart)
                .map(policy -> new GlueAllowancePolicyView(
                        policy.policyBusinessId(), policy.effectiveMonth(), policy.version(),
                        WeightGram.ofGrams(policy.exemptWeightGrams()), policy.reasonText()))
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_GLUE_POLICY_MISSING", "该月尚无胶水设置"));
    }

    @Override
    public GlueMonthReferenceView getMonthReference(
            String employeeBusinessId, LocalDate monthStart) {
        authorization.require(PermissionCodes.PAYROLL_SENSITIVE_READ, ResourceScope.GLOBAL);
        var reference = glue.findReference(employeeBusinessId, monthStart)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_GLUE_REFERENCE_MISSING", "胶水月参考不存在"));
        return toView(reference);
    }

    @Override
    public GlueMonthReferenceView runMonthReference(RunGlueMonthReferenceCommand command) {
        authorization.require(PermissionCodes.PAYROLL_CALCULATE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_GLUE_RUN", "胶水月参考命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        RUN_SCOPE, command.idempotencyKey(),
                        sha256(command.employeeBusinessId() + "|" + command.monthStart())),
                () -> new CommandOutcome(
                        "PAYROLL_GLUE_REFERENCE",
                        doRun(command.employeeBusinessId(), command.monthStart()))).outcome());
        var reference = glue.findReference(
                command.employeeBusinessId(), command.monthStart()).orElseThrow();
        return toView(reference);
    }

    private String doPublish(PublishGlueAllowancePolicyCommand command) {
        long guard = glue.lockGuardAndReadVersion();
        if (guard != command.expectedGuardVersion()) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "PAYROLL_GLUE_POLICY_CONFLICT",
                    "胶水设置已被其他操作修改，请重新读取后重试");
        }
        long version = glue.nextPolicyVersion(command.effectiveMonth());
        String id = "GLP-" + UUID.randomUUID();
        glue.insertPolicy(new PayrollGlueRepository.PolicyRecord(
                id, command.effectiveMonth(), version,
                command.exemptWeightGrams().value(), command.reasonText().strip(),
                clock.instant(),
                currentActor.requireCurrentActor().accountBusinessId().value()));
        glue.updateGuardVersion(guard + 1);
        return id;
    }

    private String doRun(String employeeId, LocalDate monthStart) {
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        var lines = glue.listEffectiveEmployeeGlueLines(employeeId, monthStart, monthEnd);
        long sourceVersion = glue.currentSourceVersion(employeeId, monthStart, monthEnd);
        var policy = glue.findPolicyForMonth(monthStart);
        var existing = glue.findReference(employeeId, monthStart);
        String referenceId = existing.map(PayrollGlueRepository.ReferenceRecord::referenceBusinessId)
                .orElse("GLR-" + UUID.randomUUID());

        if (!lines.isEmpty() && policy.isEmpty()) {
            BigDecimal allocated = lines.stream()
                    .map(line -> line.glueGramsPerPiece()
                            .multiply(BigDecimal.valueOf(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            glue.upsertReference(new PayrollGlueRepository.ReferenceRecord(
                    referenceId, employeeId, monthStart, null, "INCOMPLETE",
                    "GLUE_ALLOWANCE_POLICY_MISSING", allocated, BigDecimal.ZERO,
                    sha256(referenceId + "|policy-missing"), sourceVersion, clock.instant()));
            glue.deleteLines(referenceId);
            return referenceId;
        }

        if (lines.isEmpty()) {
            glue.upsertReference(new PayrollGlueRepository.ReferenceRecord(
                    referenceId, employeeId, monthStart,
                    policy.map(PayrollGlueRepository.PolicyRecord::policyBusinessId).orElse(null),
                    "READY", null, BigDecimal.ZERO, BigDecimal.ZERO,
                    sha256(referenceId + "|empty|" + sourceVersion), sourceVersion,
                    clock.instant()));
            glue.deleteLines(referenceId);
            return referenceId;
        }

        var inputLines = lines.stream().map(line -> new MonthlyGlueAllowanceInput
                        .GlueResponsibilityLine(
                        line.businessDate(), line.responsibilityChainBusinessId(),
                        "GLUE|" + line.dispositionFactBusinessId(),
                        WeightGram.ofGrams(line.glueGramsPerPiece()
                                .multiply(BigDecimal.valueOf(line.quantity()))),
                        line.glueUnitPricePerGram()))
                .toList();
        MonthlyGlueAllowanceResult result = CALCULATOR.calculate(
                new MonthlyGlueAllowanceInput(
                        employeeId, policy.get().policyBusinessId(),
                        WeightGram.ofGrams(policy.get().exemptWeightGrams()), inputLines));

        String fingerprint = fingerprint(result, sourceVersion);
        String status = result.complete() ? "READY" : "INCOMPLETE";
        glue.upsertReference(new PayrollGlueRepository.ReferenceRecord(
                referenceId, employeeId, monthStart, policy.get().policyBusinessId(), status,
                result.complete() ? null : "RESPONSIBILITY_BASIS_MISSING",
                result.totalAllocatedWeight().value(), result.totalAllowedWeight().value(),
                fingerprint, sourceVersion, clock.instant()));
        glue.deleteLines(referenceId);
        for (MonthlyGlueAllowanceResult.GlueLineResult line : result.lines()) {
            glue.insertLine(new PayrollGlueRepository.LineRecord(
                    "GLL-" + UUID.randomUUID(), referenceId,
                    chainOf(lines, line.costLineId()), line.costLineId(), line.businessDate(),
                    line.allocatedWeight().value(), line.allowedWeight().value(),
                    line.chargedWeight().value(),
                    unitPriceOf(lines, line.costLineId()),
                    line.beforeReference() == null ? null : line.beforeReference().value(),
                    line.afterReference() == null ? null : line.afterReference().value(),
                    clock.instant()));
        }
        return referenceId;
    }

    private GlueMonthReferenceView toView(PayrollGlueRepository.ReferenceRecord reference) {
        LocalDate monthEnd = reference.monthStart()
                .withDayOfMonth(reference.monthStart().lengthOfMonth());
        long currentSource = glue.currentSourceVersion(
                reference.employeeBusinessId(), reference.monthStart(), monthEnd);
        String currentPolicy = glue.findPolicyForMonth(reference.monthStart())
                .map(PayrollGlueRepository.PolicyRecord::policyBusinessId).orElse(null);
        boolean stale = currentSource != reference.sourceVersion()
                || !java.util.Objects.equals(
                        currentPolicy, reference.policyBusinessId());
        List<GlueMonthReferenceView.GlueLineView> lines = glue
                .listLines(reference.referenceBusinessId()).stream()
                .map(line -> new GlueMonthReferenceView.GlueLineView(
                        line.lineBusinessId(), line.responsibilityChainBusinessId(),
                        line.costLineKey(), line.businessDate(), line.allocatedWeight(),
                        line.exemptWeight(), line.chargeableWeight(), line.glueUnitPrice(),
                        line.preAmount(), line.postAmount()))
                .toList();
        return new GlueMonthReferenceView(
                reference.referenceBusinessId(), reference.employeeBusinessId(),
                reference.monthStart(), reference.policyBusinessId(), reference.status(),
                reference.incompleteReason(), reference.allocatedWeight(),
                reference.exemptWeight(), reference.fingerprint(), stale, lines);
    }

    private static String chainOf(
            List<PayrollGlueRepository.GlueSourceLine> lines, String costLineId) {
        return lines.stream()
                .filter(line -> ("GLUE|" + line.dispositionFactBusinessId()).equals(costLineId))
                .map(PayrollGlueRepository.GlueSourceLine::responsibilityChainBusinessId)
                .findFirst().orElse("");
    }

    private static BigDecimal unitPriceOf(
            List<PayrollGlueRepository.GlueSourceLine> lines, String costLineId) {
        return lines.stream()
                .filter(line -> ("GLUE|" + line.dispositionFactBusinessId()).equals(costLineId))
                .map(PayrollGlueRepository.GlueSourceLine::glueUnitPricePerGram)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private static String fingerprint(
            MonthlyGlueAllowanceResult result, long sourceVersion) {
        StringBuilder builder = new StringBuilder(result.employeeId())
                .append('|').append(result.policyBusinessId())
                .append('|').append(sourceVersion);
        result.lines().forEach(line -> builder.append('|').append(line.costLineId())
                .append(':').append(line.allowedWeight().value().toPlainString())
                .append(':').append(line.chargedWeight().value().toPlainString()));
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
