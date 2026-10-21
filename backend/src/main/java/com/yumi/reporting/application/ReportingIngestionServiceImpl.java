package com.yumi.reporting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.customer.api.CustomerReportingFeed;
import com.yumi.finance.cash.api.CashReportingFeed;
import com.yumi.finance.costledger.api.CostReportingFeed;
import com.yumi.finance.payroll.api.PayrollReportingFeed;
import com.yumi.orders.aftersales.api.AfterSalesReportingFeed;
import com.yumi.orders.api.OrderReportingFeed;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.product.api.ProductReportingFeed;
import com.yumi.production.api.ProductionReportingFeed;
import com.yumi.reporting.api.IngestReportingSourcesCommand;
import com.yumi.reporting.api.ReportingIngestionRef;
import com.yumi.reporting.api.ReportingIngestionService;
import com.yumi.workforce.api.WorkforceReportingFeed;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 报表来源归集实现（基线 14 §6、§8）。
 *
 * <p>只调用各模块公开 Feed，不跨模块 SQL 取数；每个 Feed 独立水位，失败记录可定位且不阻塞业务。
 */
public class ReportingIngestionServiceImpl implements ReportingIngestionService {

    private static final int PAGE_SIZE = 100;
    private static final String SCOPE = "reporting.ingest_sources";

    private final Map<String, FeedReader> readers = new LinkedHashMap<>();
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotentCommandService idempotency;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public ReportingIngestionServiceImpl(
            JdbcClient jdbc,
            CustomerReportingFeed customerFeed,
            ProductReportingFeed productFeed,
            WorkforceReportingFeed workforceFeed,
            ProductionReportingFeed productionFeed,
            OrderReportingFeed orderFeed,
            AfterSalesReportingFeed afterSalesFeed,
            PayrollReportingFeed payrollFeed,
            CostReportingFeed costFeed,
            CashReportingFeed cashFeed,
            ObjectMapper objectMapper,
            IdempotentCommandService idempotency,
            AuthorizationService authorization,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        readers.put("CUSTOMER", (cursor, limit) -> {
            var page = customerFeed.changedSince(cursor, limit);
            return new FeedPage(page.items().stream()
                    .map(item -> new FeedItem(item.customerBusinessId(), item.contentVersion(),
                            item, item.occurredAt())).toList(),
                    page.nextCursor());
        });
        readers.put("PRODUCT", (cursor, limit) -> {
            var page = productFeed.changedSince(read(cursor, ProductReportingFeed.ProductChangeCursor.class), limit);
            return new FeedPage(page.items().stream()
                    .map(item -> new FeedItem(item.productBusinessId(), item.contentVersion(),
                            item, item.updatedAt())).toList(),
                    write(page.nextCursor()));
        });
        readers.put("WORKFORCE", (cursor, limit) -> {
            var page = workforceFeed.changedSince(
                    read(cursor, WorkforceReportingFeed.WorkforceChangeCursor.class), limit);
            return new FeedPage(page.items().stream()
                    .map(item -> new FeedItem(
                            item.subjectKind() + ":" + item.subjectBusinessId(),
                            item.contentVersion(), item, item.updatedAt())).toList(),
                    write(page.nextCursor()));
        });
        readers.put("PRODUCTION", (cursor, limit) -> {
            Cursor2 current = read(cursor, Cursor2.class);
            var page = productionFeed.listAfter(
                    current == null ? Instant.EPOCH : Instant.ofEpochMilli(current.updatedAt()),
                    current == null ? "" : current.id(), limit);
            return new FeedPage(page.rows().stream()
                    .map(item -> new FeedItem(item.demandBusinessId(),
                            item.updatedAt().toEpochMilli(), item, item.updatedAt())).toList(),
                    page.hasMore() ? write(new Cursor2(
                            page.nextUpdatedAt().toEpochMilli(), page.nextDemandBusinessId())) : null);
        });
        readers.put("ORDER", (cursor, limit) -> {
            var page = orderFeed.changedSince(
                    read(cursor, OrderReportingFeed.OrderChangeCursor.class), limit);
            return new FeedPage(page.items().stream()
                    .map(item -> new FeedItem(item.orderBusinessId(), item.contentVersion(),
                            item, item.updatedAt())).toList(),
                    write(page.nextCursor()));
        });
        readers.put("AFTER_SALES", (cursor, limit) -> {
            var page = afterSalesFeed.changedSince(
                    read(cursor, AfterSalesReportingFeed.AfterSalesChangeCursor.class), limit);
            return new FeedPage(page.items().stream()
                    .map(item -> new FeedItem(item.caseBusinessId(), item.contentVersion(),
                            item, item.updatedAt())).toList(),
                    write(page.nextCursor()));
        });
        readers.put("PAYROLL", (cursor, limit) -> {
            var page = payrollFeed.changedSince(
                    read(cursor, PayrollReportingFeed.PayrollChangeCursor.class), limit);
            return new FeedPage(page.items().stream()
                    .map(item -> new FeedItem(item.resultBusinessId(), item.contentVersion(),
                            item, item.updatedAt())).toList(),
                    write(page.nextCursor()));
        });
        readers.put("COST", (cursor, limit) -> {
            var page = costFeed.changedSince(
                    read(cursor, CostReportingFeed.CostChangeCursor.class), limit);
            return new FeedPage(page.items().stream()
                    .map(item -> new FeedItem(item.costEntryBusinessId(), item.feedSequence(),
                            item, item.occurredAt())).toList(),
                    write(page.nextCursor()));
        });
        readers.put("CASH", (cursor, limit) -> {
            var page = cashFeed.changedSince(
                    read(cursor, CashReportingFeed.CashChangeCursor.class), limit);
            return new FeedPage(page.items().stream()
                    .map(item -> new FeedItem(item.cashFactBusinessId(), item.feedSequence(),
                            item, item.occurredAt())).toList(),
                    write(page.nextCursor()));
        });
    }

    @Override
    public ReportingIngestionRef ingest(IngestReportingSourcesCommand command) {
        authorization.require(PermissionCodes.REPORTING_ADMIN, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_INGESTION", "报表归集命令不能为空");
        }
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(SCOPE, command.idempotencyKey(), sha256("reporting")),
                () -> {
                    int[] counts = doIngest();
                    return new CommandOutcome(
                            "REPORTING_RUN", "RUN|" + counts[0] + "|" + counts[1]);
                }).outcome());
        String[] parts = outcome.resultBusinessId().split("\\|");
        return new ReportingIngestionRef(
                Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private int[] doIngest() {
        String owner = acquireLease();
        int records = 0;
        int failures = 0;
        try {
        for (var entry : readers.entrySet()) {
            String feed = entry.getKey();
            String cursor = currentCursor(feed);
            try {
                while (true) {
                    FeedPage page = entry.getValue().read(cursor, PAGE_SIZE);
                    for (FeedItem item : page.items()) {
                        upsertRecord(feed, item);
                        records++;
                    }
                    if (page.nextCursor() == null) {
                        upsertCursor(feed, cursor, "READY", null);
                        break;
                    }
                    upsertCursor(feed, page.nextCursor(), "READY", null);
                    cursor = page.nextCursor();
                }
            } catch (Exception exception) {
                recordFailure(feed, exception);
                upsertCursor(feed, cursor, "FAILED", exception.getMessage());
                failures++;
            }
        }
        return new int[] {records, failures};
        } finally {
            jdbc.sql("""
                            DELETE FROM reporting_feed_leases
                            WHERE feed_code = 'ALL' AND owner_token = :owner
                            """).param("owner", owner).update();
        }
    }

    private String acquireLease() {
        String owner = "RPT-" + UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT IGNORE INTO reporting_feed_leases (feed_code, owner_token, expires_at)
                        VALUES ('ALL', :owner, UTC_TIMESTAMP(6) + INTERVAL 2 MINUTE)
                        """).param("owner", owner).update();
        if (inserted == 1) {
            return owner;
        }
        int updated = jdbc.sql("""
                        UPDATE reporting_feed_leases
                        SET owner_token = :owner, expires_at = UTC_TIMESTAMP(6) + INTERVAL 2 MINUTE
                        WHERE feed_code = 'ALL'
                          AND (owner_token = :owner OR expires_at < UTC_TIMESTAMP(6))
                        """).param("owner", owner).update();
        if (updated != 1) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "REPORTING_LEASE_BUSY",
                    "报表归集正由其他执行者处理");
        }
        return owner;
    }

    private String currentCursor(String feed) {
        return jdbc.sql("SELECT cursor_json FROM reporting_feed_cursors WHERE feed_code = :feed")
                .param("feed", feed)
                .query(String.class).optional().orElse(null);
    }

    private void upsertCursor(String feed, String cursor, String status, String error) {
        jdbc.sql("""
                        INSERT INTO reporting_feed_cursors (
                            feed_code, cursor_json, status, last_error, updated_at)
                        VALUES (:feed, :cursor, :status, :error, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE cursor_json = VALUES(cursor_json),
                            status = VALUES(status), last_error = VALUES(last_error),
                            updated_at = UTC_TIMESTAMP(6)
                        """)
                .param("feed", feed)
                .param("cursor", cursor)
                .param("status", status)
                .param("error", error)
                .update();
    }

    private void upsertRecord(String feed, FeedItem item) {
        jdbc.sql("""
                        INSERT INTO reporting_projection_records (
                            feed_code, source_business_id, source_version, payload_json,
                            occurred_at, updated_at)
                        VALUES (:feed, :sourceId, :version, :payload, :occurredAt, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE payload_json = VALUES(payload_json),
                            occurred_at = VALUES(occurred_at), updated_at = UTC_TIMESTAMP(6)
                        """)
                .param("feed", feed)
                .param("sourceId", item.sourceBusinessId())
                .param("version", item.sourceVersion())
                .param("payload", write(item.payload()))
                .param("occurredAt", JdbcTimes.toUtc(item.occurredAt()))
                .update();
    }

    private void recordFailure(String feed, Exception exception) {
        jdbc.sql("""
                        INSERT INTO reporting_feed_failures (
                            failure_business_id, feed_code, reason_text, last_error, status,
                            created_at, updated_at)
                        VALUES (:id, :feed, 'FEED_INGESTION_FAILED', :error, 'PENDING',
                                UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """)
                .param("id", "RPF-" + UUID.randomUUID())
                .param("feed", feed)
                .param("error", exception.getMessage())
                .update();
    }

    private <T> T read(String cursor, Class<T> type) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(cursor, type);
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "REPORTING_CURSOR_INVALID", "报表游标无法解析");
        }
    }

    private String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "REPORTING_CURSOR_INVALID", "报表游标无法序列化");
        }
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

    private record FeedItem(
            String sourceBusinessId, long sourceVersion, Object payload, Instant occurredAt) {
    }

    private record FeedPage(List<FeedItem> items, String nextCursor) {
    }

    private record Cursor2(long updatedAt, String id) {
    }

    @FunctionalInterface
    private interface FeedReader {
        FeedPage read(String cursor, int limit);
    }
}
