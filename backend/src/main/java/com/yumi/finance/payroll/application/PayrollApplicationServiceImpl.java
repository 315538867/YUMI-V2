package com.yumi.finance.payroll.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.calculation.api.MoneyAllocationCalculator;
import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollCalculationRef;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollReviewRef;
import com.yumi.finance.payroll.api.PayrollReviewView;
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
import com.yumi.production.api.ProductionPayrollMaterialSourceFeed;
import com.yumi.workforce.api.WorkforceEmployeeDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工资期间与基础参考实现（基线 13 §5、§8.1）。
 *
 * <p>参考按员工逐项形成劳动、材料和责任行；只做参考，不生成应付、成本或自动扣款。
 * 已核定员工不被刷新覆盖。
 */
public class PayrollApplicationServiceImpl implements PayrollApplicationService, PayrollQuery {

    private static final String PERIOD_SCOPE = "finance.payroll.create_period";
    private static final String CALCULATE_SCOPE = "finance.payroll.calculate";
    private static final MoneyAllocationCalculator ALLOCATOR = MoneyAllocationCalculator.create();

    private final PayrollRepository payroll;
    private final PayrollSourceRepository sources;
    private final ProductionPayrollMaterialSourceFeed materialFeed;
    private final WorkforceEmployeeDirectory employees;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PayrollApplicationServiceImpl(
            PayrollRepository payroll,
            PayrollSourceRepository sources,
            ProductionPayrollMaterialSourceFeed materialFeed,
            WorkforceEmployeeDirectory employees,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.payroll = payroll;
        this.sources = sources;
        this.materialFeed = materialFeed;
        this.employees = employees;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public PayrollPeriodRef createPeriod(CreatePayrollPeriodCommand command) {
        authorization.require(PermissionCodes.PAYROLL_PERIOD_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PERIOD", "工资期间命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        PERIOD_SCOPE, command.idempotencyKey(),
                        sha256(command.periodNo() + "|" + command.startDate() + "|"
                                + command.endDate())),
                () -> new CommandOutcome(
                        "PAYROLL_PERIOD", doCreatePeriod(command))).outcome());
        return toRef(requirePeriod(outcome.resultBusinessId()));
    }

    @Override
    public PayrollCalculationRef calculate(CalculatePayrollCommand command) {
        authorization.require(PermissionCodes.PAYROLL_CALCULATE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CALCULATION", "工资参考命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        CALCULATE_SCOPE, command.idempotencyKey(),
                        sha256(command.periodBusinessId())),
                () -> {
                    int[] counts = doCalculate(command.periodBusinessId());
                    return new CommandOutcome(
                            "PAYROLL_CALCULATION", "CAL|" + counts[0] + "|" + counts[1]);
                }).outcome());
        String[] parts = outcome.resultBusinessId().split("\\|");
        return new PayrollCalculationRef(
                command.periodBusinessId(), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    @Override
    public List<PayrollPeriodRef> listPeriods() {
        authorization.require(PermissionCodes.PAYROLL_READ, ResourceScope.GLOBAL);
        return payroll.listPeriods().stream().map(PayrollApplicationServiceImpl::toRef).toList();
    }

    @Override
    public List<EmployeePayrollView> listPeriodResults(String periodBusinessId) {
        authorization.require(PermissionCodes.PAYROLL_SENSITIVE_READ, ResourceScope.GLOBAL);
        requirePeriod(periodBusinessId);
        return payroll.listResults(periodBusinessId).stream().map(this::toView).toList();
    }

    @Override
    public EmployeePayrollView getEmployeePayroll(
            String periodBusinessId, String employeeBusinessId) {
        authorization.require(PermissionCodes.PAYROLL_SENSITIVE_READ, ResourceScope.GLOBAL);
        var result = payroll.findResult(periodBusinessId, employeeBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        return toView(result);
    }

    private String doCreatePeriod(CreatePayrollPeriodCommand command) {
        var existing = payroll.findPeriodByNo(command.periodNo());
        if (existing.isPresent()) {
            var period = existing.get();
            if (!period.startDate().equals(command.startDate())
                    || !period.endDate().equals(command.endDate())) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PAYROLL_PERIOD_CONFLICT",
                        "期间号已用于其他日期范围");
            }
            return period.periodBusinessId();
        }
        if (payroll.periodsOverlap(command.startDate(), command.endDate())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_PERIOD_OVERLAP",
                    "工资期间与既有期间重叠");
        }
        String id = "PYP-" + UUID.randomUUID();
        payroll.insertPeriod(new PayrollRepository.PeriodRecord(
                id, command.periodNo().strip(), command.startDate(), command.endDate(),
                "OPEN", 1L, 1L, clock.instant()));
        return id;
    }

    private int[] doCalculate(String periodBusinessId) {
        var period = requirePeriod(periodBusinessId);
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        var laborEntries = sources.laborEntriesBetween(period.startDate(), period.endDate());
        var responsibilityEntries =
                sources.responsibilityEntriesBetween(period.startDate(), period.endDate());
        var materialEntries = materialFeed.listForPeriod(period.startDate(), period.endDate());
        var employees = payroll.distinctLaborEmployees(period.startDate(), period.endDate());

        int lineCount = 0;
        for (String employeeId : employees) {
            var existing = payroll.findResult(periodBusinessId, employeeId);
            if (existing.isPresent() && "CONFIRMED".equals(existing.get().status())) {
                continue;
            }
            lineCount += writeReference(
                    period, employeeId, "BASE", null, true,
                    laborEntries, responsibilityEntries, materialEntries).lineCount();
        }
        return new int[] {employees.size(), lineCount};
    }

    /** 对已核定员工生成独立只读复核运行，不修改原核定、应付或成本。 */
    public PayrollReviewRef runReview(String resultBusinessId) {
        var result = payroll.findResultById(resultBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_RESULT_NOT_FOUND", "员工工资结果不存在"));
        if (!"CONFIRMED".equals(result.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PAYROLL_NOT_CONFIRMED",
                    "只有已核定员工可以生成复核运行");
        }
        var period = requirePeriod(result.periodBusinessId());
        var laborEntries = sources.laborEntriesBetween(period.startDate(), period.endDate());
        var responsibilityEntries =
                sources.responsibilityEntriesBetween(period.startDate(), period.endDate());
        var materialEntries = materialFeed.listForPeriod(period.startDate(), period.endDate());
        ReferenceWrite written = writeReference(
                period, result.employeeBusinessId(), "REVIEW", result.referenceRunBusinessId(),
                false, laborEntries, responsibilityEntries, materialEntries);
        return new PayrollReviewRef(
                resultBusinessId, written.runId(), written.fingerprint(), written.lineCount());
    }

    private ReferenceWrite writeReference(
            PayrollRepository.PeriodRecord period, String employeeId, String runType,
            String referencedRunId, boolean updateResult,
            List<PayrollSourceRepository.LaborEntry> laborEntries,
            List<PayrollSourceRepository.ResponsibilityEntry> responsibilityEntries,
            List<ProductionPayrollMaterialSourceFeed.MaterialItem> materialEntries) {
        List<PayrollRepository.ReferenceLineRecord> lines = new ArrayList<>();
        buildLaborLines(employeeId, laborEntries, period, lines);
        buildMaterialLines(employeeId, laborEntries, materialEntries, lines);
        buildResponsibilityLines(employeeId, responsibilityEntries, lines);
        String runId = "PYRUN-" + UUID.randomUUID();
        String fingerprint = fingerprint(period, employeeId, lines);
        String versions = sourceVersions(employeeId, period);
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        payroll.insertReferenceRun(new PayrollRepository.ReferenceRunRecord(
                runId, period.periodBusinessId(), employeeId, runType, fingerprint, versions,
                referencedRunId, now, operatorId));
        for (var line : lines) {
            payroll.insertReferenceLine(new PayrollRepository.ReferenceLineRecord(
                    "PYL-" + UUID.randomUUID(), runId, line.lineType(), line.sourceBusinessId(),
                    line.componentCode(), line.quantity(), line.unitAmount(),
                    line.referenceAmount(), line.occurred(), line.estimated(),
                    line.materialSource(), line.incomplete(), line.note(), line.occurredAt()));
        }
        if (updateResult) {
            var result = payroll.ensureResult(period.periodBusinessId(), employeeId);
            payroll.updateResultReference(
                    result.resultBusinessId(), runId, fingerprint, "REFERENCE_READY");
        }
        return new ReferenceWrite(runId, fingerprint, lines.size());
    }

    private record ReferenceWrite(String runId, String fingerprint, int lineCount) {
    }

    private void buildLaborLines(
            String employeeId, List<PayrollSourceRepository.LaborEntry> entries,
            PayrollRepository.PeriodRecord period,
            List<PayrollRepository.ReferenceLineRecord> lines) {
        for (var entry : entries) {
            if (!"APPLY".equals(entry.effectiveStatus())) {
                continue;
            }
            if ("TEAM".equals(entry.subjectType())) {
                if (!entry.participants().contains(employeeId)) {
                    continue;
                }
                if (entry.quantity() == null || entry.unitLaborAmount() == null) {
                    lines.add(line("LABOR", entry.sourceLaborSourceBusinessId(), null,
                            entry.quantity(), entry.unitLaborAmount(), null, true, false, null,
                            true, "缺少计件人工标准", entry.occurredAt()));
                    continue;
                }
                BigDecimal gross = entry.unitLaborAmount()
                        .multiply(BigDecimal.valueOf(entry.quantity()))
                        .setScale(2, RoundingMode.HALF_UP);
                var split = ALLOCATOR.splitEqually(
                        SettlementMoney.of(gross, CurrencyCode.CNY),
                        entry.participants().stream().sorted().toList());
                split.shares().stream()
                        .filter(share -> share.employeeId().equals(employeeId))
                        .findFirst()
                        .ifPresent(share -> lines.add(line(
                                "LABOR", entry.sourceLaborSourceBusinessId(), null,
                                entry.quantity(), entry.unitLaborAmount(),
                                share.amount().value(), true, false, null, false,
                                "小组任务按历史成员均分", entry.occurredAt())));
                continue;
            }
            if (!entry.subjectBusinessId().equals(employeeId)) {
                continue;
            }
            if (entry.quantity() == null || entry.unitLaborAmount() == null) {
                lines.add(line("LABOR", entry.sourceLaborSourceBusinessId(), null,
                        entry.quantity(), entry.unitLaborAmount(), null, true, false, null,
                        true, "缺少计件人工标准", entry.occurredAt()));
                continue;
            }
            BigDecimal amount = entry.unitLaborAmount()
                    .multiply(BigDecimal.valueOf(entry.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            lines.add(line("LABOR", entry.sourceLaborSourceBusinessId(), null,
                    entry.quantity(), entry.unitLaborAmount(), amount, true, false, null,
                    false, "计件人工参考", entry.occurredAt()));
        }
    }

    private void buildMaterialLines(
            String employeeId, List<PayrollSourceRepository.LaborEntry> laborEntries,
            List<ProductionPayrollMaterialSourceFeed.MaterialItem> materials,
            List<PayrollRepository.ReferenceLineRecord> lines) {
        var tasks = laborEntries.stream()
                .filter(entry -> entry.participants().contains(employeeId))
                .map(PayrollSourceRepository.LaborEntry::taskBusinessId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Boolean> estimatedSeen = new LinkedHashMap<>();
        for (var material : materials) {
            if (!tasks.contains(material.taskBusinessId())) {
                continue;
            }
            boolean customerSupply = "CUSTOMER".equals(material.materialSource());
            BigDecimal amount = customerSupply || !material.basisComplete()
                    ? null
                    : material.unitCost().multiply(BigDecimal.valueOf(material.quantity()))
                            .setScale(2, RoundingMode.HALF_UP);
            lines.add(line(
                    "MATERIAL", material.materialSourceBusinessId(), material.componentCode(),
                    material.quantity(), material.unitCost(), amount, true, false,
                    material.materialSource(), !material.basisComplete(),
                    customerSupply ? "客户供料，不计工作室成本"
                            : (material.basisComplete() ? "材料参考" : "缺少历史材料依据"),
                    material.occurredAt()));
            int currentProcess = processOrdinal(laborEntries, material.taskBusinessId());
            for (var basisLine : material.basisCostLines()) {
                int lineProcess = basisLine.processCode() == null
                        ? -1 : processOrdinal(basisLine.processCode());
                if (lineProcess <= currentProcess) {
                    continue;
                }
                String key = material.taskBusinessId() + "|" + basisLine.componentCode()
                        + "|" + basisLine.processCode();
                if (estimatedSeen.putIfAbsent(key, true) != null) {
                    continue;
                }
                lines.add(line(
                        "LABOR".equals(basisLine.nature()) ? "LABOR" : "MATERIAL",
                        material.materialSourceBusinessId(), basisLine.componentCode(), null,
                        basisLine.unitCost(), null, false, true, basisLine.source(), false,
                        "未发生工序标准参考：" + basisLine.processCode(), material.occurredAt()));
            }
        }
    }

    private void buildResponsibilityLines(
            String employeeId, List<PayrollSourceRepository.ResponsibilityEntry> entries,
            List<PayrollRepository.ReferenceLineRecord> lines) {
        for (var entry : entries) {
            if (!"APPLY".equals(entry.effectType())
                    || !"EMPLOYEE".equals(entry.responsibilityType())
                    || !employeeId.equals(entry.responsibleSubjectBusinessId())) {
                continue;
            }
            lines.add(line("RESPONSIBILITY", entry.sourceRevisionBusinessId(), null,
                    entry.quantity(), null, null, true, false, null, false,
                    "报废责任数量 " + entry.quantity() + "，不自动扣款", entry.occurredAt()));
        }
    }

    private static int processOrdinal(
            List<PayrollSourceRepository.LaborEntry> entries, String taskBusinessId) {
        return entries.stream()
                .filter(entry -> entry.taskBusinessId().equals(taskBusinessId))
                .map(PayrollSourceRepository.LaborEntry::processCode)
                .filter(code -> code != null)
                .mapToInt(PayrollApplicationServiceImpl::processOrdinal)
                .min().orElse(-1);
    }

    private static int processOrdinal(String processCode) {
        try {
            return com.yumi.production.api.TargetProcess.valueOf(processCode).ordinal();
        } catch (IllegalArgumentException exception) {
            return -1;
        }
    }

    private static PayrollRepository.ReferenceLineRecord line(
            String type, String sourceId, String componentCode, Integer quantity,
            BigDecimal unitAmount, BigDecimal amount, boolean occurred, boolean estimated,
            String materialSource, boolean incomplete, String note, Instant occurredAt) {
        return new PayrollRepository.ReferenceLineRecord(
                "", "", type, sourceId, componentCode, quantity, unitAmount, amount, occurred,
                estimated, materialSource, incomplete, note, occurredAt);
    }

    private String sourceVersions(String employeeId, PayrollRepository.PeriodRecord period) {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("periodContentVersion", period.contentVersion());
        versions.put("monthGuard", payroll.monthGuardVersion(
                employeeId, period.startDate().withDayOfMonth(1)));
        try {
            return objectMapper.writeValueAsString(versions);
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PAYROLL_SOURCE_VERSION_INVALID", "来源版本无法序列化");
        }
    }

    private static String fingerprint(
            PayrollRepository.PeriodRecord period, String employeeId,
            List<PayrollRepository.ReferenceLineRecord> lines) {
        StringBuilder builder = new StringBuilder(period.periodBusinessId())
                .append('|').append(employeeId);
        lines.stream().sorted(Comparator.comparing(PayrollRepository.ReferenceLineRecord::sourceBusinessId)
                        .thenComparing(PayrollRepository.ReferenceLineRecord::lineType))
                .forEach(line -> builder.append('|').append(line.lineType())
                        .append(':').append(line.sourceBusinessId())
                        .append(':').append(line.referenceAmount()));
        return sha256(builder.toString());
    }

    @Override
    public PayrollReviewView getReview(String reviewRunBusinessId) {
        authorization.require(PermissionCodes.PAYROLL_READ, ResourceScope.GLOBAL);
        var run = payroll.findRun(reviewRunBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_REVIEW_NOT_FOUND", "工资复核运行不存在"));
        List<EmployeePayrollView.ReferenceLineView> lines = payroll.listReferenceLines(
                        run.runBusinessId()).stream()
                .map(line -> new EmployeePayrollView.ReferenceLineView(
                        line.lineBusinessId(), line.lineType(), line.sourceBusinessId(),
                        line.componentCode(), line.quantity(), line.unitAmount(),
                        line.referenceAmount() == null ? null
                                : SettlementMoney.of(line.referenceAmount(), CurrencyCode.CNY),
                        line.occurred(), line.estimated(), line.materialSource(),
                        line.incomplete(), line.note()))
                .toList();
        return new PayrollReviewView(
                run.runBusinessId(), run.periodBusinessId(), run.employeeBusinessId(),
                run.runType(), run.fingerprint(), run.generatedAt(), lines);
    }

    private EmployeePayrollView toView(PayrollRepository.ResultRecord result) {
        List<EmployeePayrollView.ReferenceLineView> lines = result.referenceRunBusinessId() == null
                ? List.of()
                : payroll.listReferenceLines(result.referenceRunBusinessId()).stream()
                .map(line -> new EmployeePayrollView.ReferenceLineView(
                        line.lineBusinessId(), line.lineType(), line.sourceBusinessId(),
                        line.componentCode(), line.quantity(), line.unitAmount(),
                        line.referenceAmount() == null ? null
                                : SettlementMoney.of(line.referenceAmount(), CurrencyCode.CNY),
                        line.occurred(), line.estimated(), line.materialSource(),
                        line.incomplete(), line.note()))
                .toList();
        var employee = employees.find(result.employeeBusinessId()).orElse(null);
        java.math.BigDecimal referenceTotal = lines.stream()
                .map(EmployeePayrollView.ReferenceLineView::referenceAmount)
                .filter(java.util.Objects::nonNull)
                .map(SettlementMoney::value)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        java.math.BigDecimal paid = payroll.paidAmount(result.resultBusinessId());
        return new EmployeePayrollView(
                result.resultBusinessId(), result.periodBusinessId(), result.employeeBusinessId(),
                employee == null ? null : employee.employeeNo(),
                employee == null ? null : employee.name(),
                result.status(), result.referenceRunBusinessId(), result.referenceFingerprint(),
                result.confirmedAmount() == null ? null
                        : SettlementMoney.of(result.confirmedAmount(), CurrencyCode.CNY),
                SettlementMoney.of(referenceTotal, CurrencyCode.CNY),
                SettlementMoney.of(result.payableAmount(), CurrencyCode.CNY),
                SettlementMoney.of(paid, CurrencyCode.CNY),
                SettlementMoney.of(result.payableAmount().subtract(paid).max(java.math.BigDecimal.ZERO), CurrencyCode.CNY),
                result.rowVersion(), lines);
    }

    private PayrollRepository.PeriodRecord requirePeriod(String periodBusinessId) {
        return payroll.findPeriod(periodBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PAYROLL_PERIOD_NOT_FOUND", "工资期间不存在"));
    }

    private static PayrollPeriodRef toRef(PayrollRepository.PeriodRecord period) {
        return new PayrollPeriodRef(
                period.periodBusinessId(), period.periodNo(), period.startDate(),
                period.endDate(), period.status(), period.contentVersion());
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
