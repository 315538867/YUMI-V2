package com.yumi.finance.costledger.application;

import com.yumi.finance.costledger.api.AccrueAfterSalesCostsCommand;
import com.yumi.finance.costledger.api.AdjustCostCommand;
import com.yumi.finance.costledger.api.AllocateCostCommand;
import com.yumi.finance.costledger.api.CostEntryRef;
import com.yumi.finance.costledger.api.RetryCostFailureCommand;
import com.yumi.finance.costledger.api.AccrueDirectLaborCostsCommand;
import com.yumi.finance.costledger.api.AccrueProductionMaterialCostsCommand;
import com.yumi.finance.costledger.api.CostAccrualRunRef;
import com.yumi.finance.costledger.api.CostLedgerApplicationService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.finance.payroll.api.PayrollCostSourceFeed;
import com.yumi.orders.aftersales.api.OrderAfterSalesCostSourceFeed;
import com.yumi.production.api.ProductionPayrollMaterialSourceFeed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 生产材料成本归集（基线 13 §8.5）。
 *
 * <p>只取当前工序、工作室承担、非人工的历史单件成本；客户供料覆盖不入成本。
 * 同一来源版本只入账一次。
 */
public class CostLedgerApplicationServiceImpl implements CostLedgerApplicationService {

    private static final String MATERIAL_SCOPE = "finance.costledger.accrue_material";
    private static final String LABOR_SCOPE = "finance.costledger.accrue_labor";
    private static final String AFTER_SALES_SCOPE = "finance.costledger.accrue_after_sales";

    private final CostLedgerRepository ledger;
    private final ProductionPayrollMaterialSourceFeed materialFeed;
    private final PayrollCostSourceFeed payrollCostFeed;
    private final OrderAfterSalesCostSourceFeed afterSalesCostFeed;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public CostLedgerApplicationServiceImpl(
            CostLedgerRepository ledger,
            ProductionPayrollMaterialSourceFeed materialFeed,
            PayrollCostSourceFeed payrollCostFeed,
            OrderAfterSalesCostSourceFeed afterSalesCostFeed,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.materialFeed = materialFeed;
        this.payrollCostFeed = payrollCostFeed;
        this.afterSalesCostFeed = afterSalesCostFeed;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public CostAccrualRunRef accrueProductionMaterialCosts(
            AccrueProductionMaterialCostsCommand command) {
        authorization.require(PermissionCodes.COST_ACCRUE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_COST_RUN", "材料成本归集命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        MATERIAL_SCOPE, command.idempotencyKey(),
                        sha256(command.startDate() + "|" + command.endDate())),
                () -> {
                    int accrued = doAccrue(command);
                    return new CommandOutcome("COST_RUN", "RUN|" + accrued + "|0");
                }).outcome());
        String[] parts = outcome.resultBusinessId().split("\\|");
        return new CostAccrualRunRef(
                outcome.resultBusinessId(), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    @Override
    public CostAccrualRunRef accrueDirectLaborCosts(AccrueDirectLaborCostsCommand command) {
        authorization.require(PermissionCodes.COST_ACCRUE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_COST_RUN", "人工成本归集命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        LABOR_SCOPE, command.idempotencyKey(),
                        sha256(command.startDate() + "|" + command.endDate())),
                () -> {
                    int accrued = doAccrueLabor(command);
                    return new CommandOutcome("COST_RUN", "RUN|" + accrued + "|0");
                }).outcome());
        String[] parts = outcome.resultBusinessId().split("\\|");
        return new CostAccrualRunRef(
                outcome.resultBusinessId(), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    @Override
    public CostAccrualRunRef accrueAfterSalesCosts(AccrueAfterSalesCostsCommand command) {
        authorization.require(PermissionCodes.COST_ACCRUE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_COST_RUN", "售后成本归集命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        AFTER_SALES_SCOPE, command.idempotencyKey(), sha256("after-sales")),
                () -> {
                    int accrued = doAccrueAfterSales();
                    return new CommandOutcome("COST_RUN", "RUN|" + accrued + "|0");
                }).outcome());
        String[] parts = outcome.resultBusinessId().split("\\|");
        return new CostAccrualRunRef(
                outcome.resultBusinessId(), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    private int doAccrueLabor(AccrueDirectLaborCostsCommand command) {
        int accrued = 0;
        var now = clock.instant();
        for (var item : payrollCostFeed.listDirectLaborCosts(
                command.startDate(), command.endDate())) {
            String sourceType = "ADJUST".equals(item.operationType())
                    ? "DIRECT_LABOR_ADJUSTMENT" : "DIRECT_LABOR";
            if (ledger.guardExists("finance", sourceType, item.sourceBusinessId(), 1L)) {
                continue;
            }
            String entryId = "CST-" + UUID.randomUUID();
            ledger.insertEntry(new CostLedgerRepository.EntryRecord(
                    entryId, "finance", sourceType, item.sourceBusinessId(), 1L,
                    "DIRECT_LABOR", "EMPLOYEE", item.employeeBusinessId(), item.amount(),
                    item.businessDate(), "ADJUST".equals(item.operationType()) ? "ADJUST" : "ACCRUE",
                    item.resultBusinessId(), null, null, item.occurredAt(), now, false));
            ledger.insertLine(new CostLedgerRepository.LineRecord(
                    "CSL-" + UUID.randomUUID(), entryId, "DIRECT_LABOR", null, "LABOR",
                    "STUDIO", 1, item.amount(), item.amount()));
            ledger.insertGuard(new CostLedgerRepository.GuardRecord(
                    "finance", sourceType, item.sourceBusinessId(), 1L, entryId, now));
            accrued++;
        }
        currentActor.requireCurrentActor();
        return accrued;
    }

    private int doAccrueAfterSales() {
        int accrued = 0;
        var now = clock.instant();
        com.yumi.orders.aftersales.api.OrderAfterSalesCostSourceFeed.AfterSalesCostCursor cursor = null;
        while (true) {
            var page = afterSalesCostFeed.listAfter(cursor, 100);
            for (var item : page.items()) {
                String sourceId = item.sourceBusinessId() + "#" + item.operationType();
                if (ledger.guardExists("orders", "AFTER_SALES_COST", sourceId,
                        item.sourceVersion())) {
                    continue;
                }
                BigDecimal amount = "REVERSE".equals(item.operationType())
                        ? item.amount().negate().setScale(4, RoundingMode.HALF_UP)
                        : item.amount().setScale(4, RoundingMode.HALF_UP);
                String entryId = "CST-" + UUID.randomUUID();
                ledger.insertEntry(new CostLedgerRepository.EntryRecord(
                        entryId, "orders", "AFTER_SALES_COST", sourceId, item.sourceVersion(),
                        "AFTER_SALES", "ORDER", item.orderBusinessId(), amount,
                        item.businessDate(), item.operationType(), item.basisRef(),
                        null, item.reasonText(), item.occurredAt(), now, false));
                ledger.insertLine(new CostLedgerRepository.LineRecord(
                        "CSL-" + UUID.randomUUID(), entryId, "AFTER_SALES", null,
                        "AFTER_SALES", "STUDIO", 1, amount, amount));
                ledger.insertGuard(new CostLedgerRepository.GuardRecord(
                        "orders", "AFTER_SALES_COST", sourceId, item.sourceVersion(), entryId, now));
                accrued++;
            }
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        currentActor.requireCurrentActor();
        return accrued;
    }

    @Override
    public CostEntryRef adjust(AdjustCostCommand command) {
        authorization.require(PermissionCodes.COST_ADJUST, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_COST_ADJUST", "成本更正命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        "finance.costledger.adjust", command.idempotencyKey(),
                        sha256(command.costEntryBusinessId() + "|"
                                + command.newAmount().value().toPlainString())),
                () -> new CommandOutcome("COST_ENTRY", doAdjust(command))).outcome());
        var entry = ledger.findEntry(outcome.resultBusinessId()).orElseThrow();
        return new CostEntryRef(
                entry.costEntryBusinessId(),
                com.yumi.platform.api.value.CostAmount.of(
                        entry.amount(), com.yumi.platform.api.value.CurrencyCode.CNY),
                entry.allocated());
    }

    @Override
    public CostEntryRef allocate(AllocateCostCommand command) {
        authorization.require(PermissionCodes.COST_ALLOCATE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_COST_ALLOCATION", "成本分摊命令不能为空");
        }
        transactionTemplate.executeWithoutResult(status -> idempotency.execute(
                new CommandRequest(
                        "finance.costledger.allocate", command.idempotencyKey(),
                        sha256(command.costEntryBusinessId())),
                () -> {
                    doAllocate(command);
                    return new CommandOutcome("COST_ENTRY", command.costEntryBusinessId());
                }));
        var entry = ledger.findEntry(command.costEntryBusinessId()).orElseThrow();
        return new CostEntryRef(
                entry.costEntryBusinessId(),
                com.yumi.platform.api.value.CostAmount.of(
                        entry.amount(), com.yumi.platform.api.value.CurrencyCode.CNY),
                entry.allocated());
    }

    @Override
    public CostAccrualRunRef retryFailure(RetryCostFailureCommand command) {
        authorization.require(PermissionCodes.COST_ACCRUE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_RETRY", "失败重试命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        "finance.costledger.retry", command.idempotencyKey(),
                        sha256(command.failureBusinessId())),
                () -> {
                    var failure = ledger.findFailure(command.failureBusinessId())
                            .orElseThrow(() -> new ApiException(
                                    ApiErrorType.NOT_FOUND, "COST_FAILURE_NOT_FOUND", "成本失败记录不存在"));
                    int accrued = switch (failure.sourceType()) {
                        case "MATERIAL_SOURCE" -> doAccrue(
                                new AccrueProductionMaterialCostsCommand(
                                        "retry", command.startDate(), command.endDate()));
                        case "DIRECT_LABOR", "DIRECT_LABOR_ADJUSTMENT" -> doAccrueLabor(
                                new AccrueDirectLaborCostsCommand(
                                        "retry", command.startDate(), command.endDate()));
                        case "AFTER_SALES_COST" -> doAccrueAfterSales();
                        default -> throw new ApiException(
                                ApiErrorType.BUSINESS_REJECTION, "COST_FAILURE_UNSUPPORTED",
                                "不支持的失败来源类型");
                    };
                    ledger.markFailureResolved(command.failureBusinessId());
                    return new CommandOutcome("COST_RUN", "RUN|" + accrued + "|0");
                }).outcome());
        String[] parts = outcome.resultBusinessId().split("\\|");
        return new CostAccrualRunRef(
                outcome.resultBusinessId(), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    private String doAdjust(AdjustCostCommand command) {
        var original = ledger.findEntry(command.costEntryBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "COST_ENTRY_NOT_FOUND", "成本事实不存在"));
        if ("REVERSE".equals(original.operationType())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "COST_ADJUST_INVALID", "反向成本事实不能再被更正");
        }
        var now = clock.instant();
        ledger.insertEntry(new CostLedgerRepository.EntryRecord(
                "CST-" + UUID.randomUUID(), original.sourceModule(), original.sourceType(),
                original.sourceBusinessId(), original.sourceVersion(), original.costCategory(),
                original.subjectType(), original.subjectBusinessId(), original.amount().negate(),
                original.businessDate(), "REVERSE", original.basisRef(),
                original.basisFingerprint(), command.reasonText().strip(),
                original.occurredAt(), now, false));
        String replacementId = "CST-" + UUID.randomUUID();
        ledger.insertEntry(new CostLedgerRepository.EntryRecord(
                replacementId, original.sourceModule(), original.sourceType(),
                original.sourceBusinessId(), original.sourceVersion() + 1,
                original.costCategory(), original.subjectType(), original.subjectBusinessId(),
                command.newAmount().value(), original.businessDate(), "ACCRUE",
                original.basisRef(), original.basisFingerprint(),
                command.reasonText().strip(), now, now, false));
        ledger.insertLine(new CostLedgerRepository.LineRecord(
                "CSL-" + UUID.randomUUID(), replacementId, "ADJUSTMENT", null,
                "ADJUSTMENT", "STUDIO", 1, command.newAmount().value(),
                command.newAmount().value()));
        currentActor.requireCurrentActor();
        return replacementId;
    }

    private void doAllocate(AllocateCostCommand command) {
        var entry = ledger.findEntry(command.costEntryBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "COST_ENTRY_NOT_FOUND", "成本事实不存在"));
        if (ledger.hasAllocations(command.costEntryBusinessId())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "COST_ALREADY_ALLOCATED", "成本事实已分摊");
        }
        BigDecimal total = command.allocations().stream()
                .map(allocation -> allocation.amount().value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(entry.amount()) != 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "COST_ALLOCATION_MISMATCH",
                    "分摊合计必须等于成本金额：成本 " + entry.amount().toPlainString()
                            + "，分摊 " + total.toPlainString());
        }
        var now = clock.instant();
        for (var allocation : command.allocations()) {
            ledger.insertAllocation(new CostLedgerRepository.AllocationRecord(
                    "CAL-" + UUID.randomUUID(), entry.costEntryBusinessId(),
                    allocation.subjectType(), allocation.subjectBusinessId(),
                    allocation.amount().value(), now));
        }
        ledger.markAllocated(entry.costEntryBusinessId());
    }

    private int doAccrue(AccrueProductionMaterialCostsCommand command) {
        int accrued = 0;
        for (var item : materialFeed.listForPeriod(command.startDate(), command.endDate())) {
            if (!"STUDIO".equals(item.materialSource())
                    || !item.basisComplete()
                    || item.quantity() <= 0
                    || item.unitCost().signum() <= 0) {
                continue;
            }
            if (ledger.guardExists("production", "MATERIAL_SOURCE",
                    item.materialSourceBusinessId(), 1L)) {
                continue;
            }
            BigDecimal amount = item.unitCost()
                    .multiply(BigDecimal.valueOf(item.quantity()))
                    .setScale(4, RoundingMode.HALF_UP);
            String entryId = "CST-" + UUID.randomUUID();
            var now = clock.instant();
            String subjectType = item.orderBusinessId() == null
                    ? "PRODUCTION_DEMAND" : "ORDER";
            String subjectId = item.orderBusinessId() == null
                    ? item.demandBusinessId() : item.orderBusinessId();
            ledger.insertEntry(new CostLedgerRepository.EntryRecord(
                    entryId, "production", "MATERIAL_SOURCE", item.materialSourceBusinessId(),
                    1L, "MATERIAL", subjectType, subjectId, amount, item.businessDate(),
                    "ACCRUE", item.materialSourceBusinessId(), null, null, item.occurredAt(), now,
                    false));
            ledger.insertLine(new CostLedgerRepository.LineRecord(
                    "CSL-" + UUID.randomUUID(), entryId, item.componentCode(), null,
                    "NON_LABOR_MATERIAL", "STUDIO", item.quantity(), item.unitCost(), amount));
            ledger.insertGuard(new CostLedgerRepository.GuardRecord(
                    "production", "MATERIAL_SOURCE", item.materialSourceBusinessId(), 1L,
                    entryId, now));
            accrued++;
        }
        currentActor.requireCurrentActor();
        return accrued;
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
