package com.yumi.orders.aftersales.application;

import com.yumi.orders.aftersales.api.AfterSalesCostRef;
import com.yumi.orders.aftersales.api.AfterSalesCostQuery;
import com.yumi.orders.aftersales.api.AfterSalesCostService;
import com.yumi.orders.aftersales.api.AfterSalesCostView;
import com.yumi.orders.aftersales.api.ConfirmAfterSalesCostCommand;
import com.yumi.orders.aftersales.api.CorrectAfterSalesCostCommand;
import com.yumi.orders.aftersales.api.OrderAfterSalesCostSourceFeed;
import com.yumi.orders.aftersales.application.port.AfterSalesRepository;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 售后成本确认、更正与来源发布（基线 12 §13.2 规则 8）。
 *
 * <p>成本来源不可变；更正发布旧版本 REVERSE 与新版本 ACCRUE。订单模块不调用财务写接口。
 */
public class AfterSalesCostServiceImpl
        implements AfterSalesCostService, AfterSalesCostQuery, OrderAfterSalesCostSourceFeed {

    private static final String CONFIRM_SCOPE = "orders.aftersales.confirm_cost";
    private static final String CORRECT_SCOPE = "orders.aftersales.correct_cost";

    private final AfterSalesRepository cases;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AfterSalesCostServiceImpl(
            AfterSalesRepository cases,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AfterSalesCostRef confirmCost(ConfirmAfterSalesCostCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_COST", "售后成本命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        CONFIRM_SCOPE, command.idempotencyKey(), fingerprint(
                                command.caseBusinessId(), command.costCategory(),
                                command.amount().value().toPlainString(),
                                command.businessDate().toString())),
                () -> new CommandOutcome(
                        "AFTER_SALES_COST", doConfirm(command))).outcome());
        return latestRef(outcome.resultBusinessId());
    }

    @Override
    public AfterSalesCostRef correctCost(CorrectAfterSalesCostCommand command) {
        authorization.require(PermissionCodes.AFTER_SALES_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_COST", "售后成本更正命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(
                        CORRECT_SCOPE, command.idempotencyKey(), fingerprint(
                                command.caseBusinessId(), command.sourceBusinessId(),
                                command.amount().value().toPlainString(),
                                command.businessDate().toString())),
                () -> new CommandOutcome(
                        "AFTER_SALES_COST", doCorrect(command))).outcome());
        return latestRef(outcome.resultBusinessId());
    }

    @Override
    public AfterSalesCostSourcePage listAfter(AfterSalesCostCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("售后成本分页大小必须在 1 到 500 之间");
        }
        var records = cases.listCostSourcesAfter(
                cursor == null ? 0 : cursor.afterSequence(), limit);
        List<AfterSalesCostSourceItem> items = records.stream()
                .map(record -> new AfterSalesCostSourceItem(
                        record.feedSequence(), record.sourceBusinessId(), record.sourceVersion(),
                        record.caseBusinessId(), record.orderBusinessId(), record.costCategory(),
                        record.amount(), record.businessDate(), record.operationType(),
                        record.basisRef(), record.reasonText(), record.occurredAt()))
                .toList();
        AfterSalesCostCursor next = records.size() < limit
                ? null : new AfterSalesCostCursor(records.getLast().feedSequence());
        return new AfterSalesCostSourcePage(items, next);
    }

    private String doConfirm(ConfirmAfterSalesCostCommand command) {
        var record = cases.lockCase(command.caseBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_CASE_NOT_FOUND", "售后案例不存在"));
        String sourceId = "ASC-" + java.util.UUID.randomUUID();
        cases.insertCostSource(new AfterSalesRepository.CostSourceRecord(
                0L, sourceId, 1, record.caseBusinessId(), record.orderBusinessId(),
                command.costCategory().strip(), command.amount().value(), command.businessDate(),
                "ACCRUE", null, command.reasonText().strip(), clock.instant()));
        return sourceId;
    }

    private String doCorrect(CorrectAfterSalesCostCommand command) {
        var record = cases.lockCase(command.caseBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_CASE_NOT_FOUND", "售后案例不存在"));
        var latest = cases.findLatestCostSource(command.sourceBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AFTER_SALES_COST_NOT_FOUND", "售后成本来源不存在"));
        if (!latest.caseBusinessId().equals(command.caseBusinessId())) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "AFTER_SALES_COST_NOT_FOUND", "成本来源不属于该案例");
        }
        Instant now = clock.instant();
        cases.insertCostSource(new AfterSalesRepository.CostSourceRecord(
                0L, command.sourceBusinessId(), latest.sourceVersion(), record.caseBusinessId(),
                record.orderBusinessId(), latest.costCategory(), latest.amount(),
                latest.businessDate(), "REVERSE", latest.sourceBusinessId(),
                command.reasonText().strip(), now));
        int nextVersion = latest.sourceVersion() + 1;
        cases.insertCostSource(new AfterSalesRepository.CostSourceRecord(
                0L, command.sourceBusinessId(), nextVersion, record.caseBusinessId(),
                record.orderBusinessId(), latest.costCategory(), command.amount().value(),
                command.businessDate(), "ACCRUE", latest.sourceBusinessId(),
                command.reasonText().strip(), now));
        return command.sourceBusinessId();
    }

    @Override
    public List<AfterSalesCostView> listByCase(String caseBusinessId) {
        authorization.require(PermissionCodes.AFTER_SALES_READ, ResourceScope.GLOBAL);
        if (caseBusinessId == null || caseBusinessId.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CASE", "售后案例不能为空");
        }
        return cases.findCostSourcesByCase(caseBusinessId).stream()
                .map(record -> new AfterSalesCostView(
                        record.sourceBusinessId(), record.caseBusinessId(),
                        record.orderBusinessId(), record.costCategory(),
                        SettlementMoney.of(record.amount(), CurrencyCode.CNY),
                        record.businessDate(), record.operationType(),
                        record.reasonText(), record.occurredAt()))
                .toList();
    }

    private AfterSalesCostRef latestRef(String sourceBusinessId) {
        var latest = cases.findLatestCostSource(sourceBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "AFTER_SALES_COST_MISSING",
                        "售后成本结果无法读取"));
        return new AfterSalesCostRef(
                latest.sourceBusinessId(), latest.sourceVersion(),
                SettlementMoney.of(latest.amount(), CurrencyCode.CNY), latest.operationType());
    }

    private static String fingerprint(String first, String second, String third, String fourth) {
        return sha256(first + "|" + second + "|" + third + "|" + fourth);
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
