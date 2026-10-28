package com.yumi.reporting.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.reporting.api.RebuildReportingProjectionCommand;
import com.yumi.reporting.api.ReportingRebuildRef;
import com.yumi.reporting.api.ReportingRebuildService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 报表影子重建实现（基线 14 §6、§8）。
 *
 * <p>从已摄取来源复制到影子版本，逐 Feed 对账；差异大于零时保留旧可用版本，
 * 对账通过后一次事务切换 ACTIVE。
 */
public class ReportingRebuildServiceImpl implements ReportingRebuildService {

    private static final String SCOPE = "reporting.rebuild";
    private static final List<String> FEEDS = List.of(
            "CUSTOMER", "PRODUCT", "WORKFORCE", "PRODUCTION", "ORDER",
            "AFTER_SALES", "PAYROLL", "COST", "CASH");

    private final ReportingRebuildRepository repository;
    private final ReportingSourceReader sourceReader;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public ReportingRebuildServiceImpl(
            ReportingRebuildRepository repository,
            ReportingSourceReader sourceReader,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.sourceReader = sourceReader;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ReportingRebuildRef rebuild(RebuildReportingProjectionCommand command) {
        authorization.require(PermissionCodes.REPORTING_ADMIN, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REBUILD", "报表重建命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(SCOPE, command.idempotencyKey(), sha256("rebuild")),
                () -> new CommandOutcome("REPORTING_VERSION", doRebuild())).outcome());
        String[] parts = outcome.resultBusinessId().split("\\|");
        return new ReportingRebuildRef(
                parts[3], Long.parseLong(parts[1]), Integer.parseInt(parts[2]),
                "ACTIVE".equals(parts[0]));
    }

    @Override
    public List<VersionView> listVersions() {
        authorization.require(PermissionCodes.REPORTING_ADMIN, ResourceScope.GLOBAL);
        return repository.listVersions().stream()
                .map(row -> new VersionView(
                        row.versionBusinessId(), row.status(), row.recordCount(),
                        row.differenceCount(), row.builtAt(), row.activatedAt()))
                .toList();
    }

    @Override
    public List<DifferenceView> listDifferences(String versionBusinessId) {
        authorization.require(PermissionCodes.REPORTING_ADMIN, ResourceScope.GLOBAL);
        return repository.listDifferences(versionBusinessId).stream()
                .map(row -> new DifferenceView(
                        row.differenceBusinessId(), row.feedCode(), row.detail()))
                .toList();
    }

    private String doRebuild() {
        var now = clock.instant();
        String versionId = "RPV-" + UUID.randomUUID();
        repository.insertVersion(versionId, "BUILDING", now);
        sourceReader.readAllInto(versionId);
        long recordCount = repository.countVersionRecords(versionId);
        int differences = 0;
        for (String feed : FEEDS) {
            long liveCount = repository.countLiveRecords(feed);
            long versionCount = repository.countVersionRecords(versionId, feed);
            Long liveVersion = repository.maxLiveSourceVersion(feed);
            Long shadowVersion = repository.maxVersionSourceVersion(versionId, feed);
            if (liveCount != versionCount
                    || !java.util.Objects.equals(liveVersion, shadowVersion)) {
                repository.insertDifference(
                        "RPD-" + UUID.randomUUID(), versionId, feed,
                        "来源计数/版本不一致：live=" + liveCount + "/" + liveVersion
                                + "，shadow=" + versionCount + "/" + shadowVersion);
                differences++;
            }
        }
        if (differences > 0) {
            repository.markFailed(versionId, differences);
            return "FAILED|" + recordCount + "|" + differences + "|" + versionId;
        }
        repository.retireActiveVersions(now);
        repository.activateVersion(versionId, recordCount, 0, now);
        repository.setActivePointer(versionId);
        return "ACTIVE|" + recordCount + "|0|" + versionId;
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
