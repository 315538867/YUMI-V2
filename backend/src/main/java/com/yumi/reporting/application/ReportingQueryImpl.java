package com.yumi.reporting.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.reporting.api.OperatingReportQuery;
import com.yumi.reporting.api.ReportingQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 报表读取实现：投影记录与水位只读。 */
public class ReportingQueryImpl implements ReportingQuery {

    private static final List<String> FEEDS = List.of(
            "CUSTOMER", "PRODUCT", "WORKFORCE", "PRODUCTION", "ORDER",
            "AFTER_SALES", "PAYROLL", "COST", "CASH");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final OperatingReportQuery operating;
    private final AuthorizationService authorization;
    private final Clock clock;

    public ReportingQueryImpl(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            OperatingReportQuery operating,
            AuthorizationService authorization,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.operating = operating;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Override
    public OperatingReportQuery.MonthlyOperatingSummary getMonthlySummary(LocalDate month) {
        return operating.getMonthlySummary(month);
    }

    @Override
    public AfterSalesMonthlySummary getAfterSalesSummary(LocalDate month) {
        authorization.require(PermissionCodes.REPORTING_READ, ResourceScope.GLOBAL);
        if (month == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REPORT_MONTH", "报表月份不能为空");
        }
        LocalDate monthStart = month.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        int total = 0;
        int open = 0;
        int completed = 0;
        for (JsonNode item : records("AFTER_SALES")) {
            JsonNode updated = item.path("updatedAt");
            if (updated.isMissingNode() || updated.isNull()
                    || LocalDate.ofInstant(Instant.parse(updated.asText()), ZoneOffset.UTC)
                            .isBefore(monthStart)
                    || LocalDate.ofInstant(Instant.parse(updated.asText()), ZoneOffset.UTC)
                            .isAfter(monthEnd)) {
                continue;
            }
            total++;
            switch (item.path("status").asText()) {
                case "OPEN", "PROCESSING" -> open++;
                case "COMPLETED" -> completed++;
                default -> {
                    // 未知状态保留在总数，不猜测归并
                }
            }
        }
        return new AfterSalesMonthlySummary(monthStart, total, open, completed);
    }

    @Override
    public FreshnessView getFreshness() {
        authorization.require(PermissionCodes.REPORTING_READ, ResourceScope.GLOBAL);
        String activeVersion = jdbc.sql("""
                        SELECT version_business_id FROM reporting_projection_active
                        WHERE singleton_id = 1
                        """).query(String.class).optional().orElse(null);
        Instant activatedAt = activeVersion == null ? null : jdbc.sql("""
                        SELECT activated_at FROM reporting_projection_versions
                        WHERE version_business_id = :id
                        """).param("id", activeVersion)
                .query((resultSet, rowNumber) ->
                        com.yumi.platform.api.jdbc.JdbcTimes.toInstant(
                                resultSet.getObject("activated_at", java.time.LocalDateTime.class)))
                .optional().orElse(null);
        List<FeedFreshness> feeds = FEEDS.stream().map(this::freshnessOf).toList();
        boolean late = feeds.stream().anyMatch(feed -> "FAILED".equals(feed.status()));
        return new FreshnessView(activeVersion, activatedAt, late, feeds);
    }

    private FeedFreshness freshnessOf(String feed) {
        return jdbc.sql("""
                        SELECT status, last_error, updated_at FROM reporting_feed_cursors
                        WHERE feed_code = :feed
                        """).param("feed", feed)
                .query((resultSet, rowNumber) -> {
                    Instant updatedAt = com.yumi.platform.api.jdbc.JdbcTimes.toInstant(
                            resultSet.getObject("updated_at", java.time.LocalDateTime.class));
                    long lag = updatedAt == null ? 0 : Math.max(
                            0, clock.instant().getEpochSecond() - updatedAt.getEpochSecond());
                    return new FeedFreshness(
                            feed, resultSet.getString("status"),
                            resultSet.getString("last_error"), updatedAt, lag);
                })
                .optional()
                .orElseGet(() -> new FeedFreshness(feed, "MISSING", null, null, 0));
    }

    private List<JsonNode> records(String feed) {
        return jdbc.sql("""
                        SELECT payload_json FROM reporting_projection_records
                        WHERE feed_code = :feed
                        """).param("feed", feed)
                .query(String.class).list().stream()
                .map(this::read)
                .filter(node -> node != null)
                .toList();
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return null;
        }
    }
}
