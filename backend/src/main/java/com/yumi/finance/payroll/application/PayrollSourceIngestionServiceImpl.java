package com.yumi.finance.payroll.application;

import com.yumi.finance.payroll.api.IngestPayrollSourcesCommand;
import com.yumi.finance.payroll.api.PayrollIngestionRef;
import com.yumi.finance.payroll.api.PayrollSourceIngestionService;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.production.api.ProductionPayrollSourceFeed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工资来源摄取实现（基线 13 §8.5、§10）。
 *
 * <p>劳动与责任各用独立游标；整页条目与游标同事务提交，失败不越过失败项，
 * 相同来源重放不重复入库。
 */
public class PayrollSourceIngestionServiceImpl implements PayrollSourceIngestionService {

    private static final String SCOPE = "finance.payroll.ingest_sources";
    private static final int PAGE_SIZE = 100;

    private final PayrollSourceRepository repository;
    private final ProductionPayrollSourceFeed productionFeed;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PayrollSourceIngestionServiceImpl(
            PayrollSourceRepository repository,
            ProductionPayrollSourceFeed productionFeed,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.productionFeed = productionFeed;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public PayrollIngestionRef ingest(IngestPayrollSourcesCommand command) {
        authorization.require(PermissionCodes.PAYROLL_CALCULATE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_INGESTION", "来源摄取命令不能为空");
        }
        currentActor.requireCurrentActor();
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(SCOPE, command.idempotencyKey(), sha256("payroll-sources")),
                () -> {
                    int[] counts = new int[2];
                    ingestLabor(counts);
                    ingestResponsibility(counts);
                    return new CommandOutcome(
                            "PAYROLL_INGESTION", "ING|" + counts[0] + "|" + counts[1]);
                }).outcome());
        String[] parts = outcome.resultBusinessId().split("\\|");
        return new PayrollIngestionRef(
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private void ingestLabor(int[] counts) {
        PayrollSourceRepository.CursorState state = repository.laborCursor();
        ProductionPayrollSourceFeed.LaborCursor cursor = state.cursorTime() == null
                ? null
                : new ProductionPayrollSourceFeed.LaborCursor(
                        state.cursorTime(), state.cursorSourceId());
        for (int page = 0; page < 1000; page++) {
            var result = productionFeed.laborAfter(cursor, PAGE_SIZE);
            for (var item : result.items()) {
                boolean inserted = repository.insertLaborEntry(
                        new PayrollSourceRepository.LaborEntry(
                                "PLE-" + UUID.randomUUID(), item.laborSourceBusinessId(),
                                item.taskBusinessId(), item.subjectType(), item.subjectBusinessId(),
                                item.participantEmployeeIds(), item.taskType(), item.processCode(),
                                item.quantity(), item.unitLaborAmount(), item.businessDate(),
                                item.occurredAt(), item.effectiveStatus(),
                                item.reversesLaborSourceBusinessId()));
                if (inserted) {
                    counts[0]++;
                    for (String employeeId : item.participantEmployeeIds()) {
                        repository.bumpMonthGuard(
                                employeeId, item.businessDate().withDayOfMonth(1));
                    }
                }
            }
            if (result.items().isEmpty()) {
                return;
            }
            var last = result.items().getLast();
            repository.updateLaborCursor(last.occurredAt(), last.laborSourceBusinessId());
            cursor = result.nextCursor();
            if (cursor == null) {
                return;
            }
        }
        throw new ApiException(
                ApiErrorType.SYSTEM_FAILURE, "PAYROLL_INGESTION_LIMIT", "工资劳动来源分页超过安全上限");
    }

    private void ingestResponsibility(int[] counts) {
        PayrollSourceRepository.CursorState state = repository.responsibilityCursor();
        long after = state.cursorSequence() == null ? 0 : state.cursorSequence();
        for (int page = 0; page < 1000; page++) {
            var result = productionFeed.responsibilityAfter(after, PAGE_SIZE);
            for (var item : result.items()) {
                boolean inserted = repository.insertResponsibilityEntry(
                        new PayrollSourceRepository.ResponsibilityEntry(
                                "PRE-" + UUID.randomUUID(), item.revisionBusinessId(),
                                item.feedSequence(), item.responsibilityChainBusinessId(),
                                item.dispositionFactBusinessId(), item.effectType(),
                                item.responsibilityType(), item.responsibleSubjectBusinessId(),
                                item.quantity(), item.glueGramsPerPiece(),
                                item.glueUnitPricePerGram(), item.glueEligible(),
                                item.businessDate(), item.occurredAt()));
                if (inserted) {
                    counts[1]++;
                }
            }
            if (result.items().isEmpty()) {
                return;
            }
            after = result.items().getLast().feedSequence();
            repository.updateResponsibilityCursor(after);
            if (result.nextSequence() == null) {
                return;
            }
        }
        throw new ApiException(
                ApiErrorType.SYSTEM_FAILURE, "PAYROLL_INGESTION_LIMIT", "工资责任来源分页超过安全上限");
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
