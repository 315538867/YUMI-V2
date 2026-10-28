package com.yumi.reporting.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 报表读取：月度经营总览、售后主题与数据时效。
 *
 * <p>只读投影，不修改业务事实；主题金额不合并成未定义的“利润”。
 */
public interface ReportingQuery {

    OperatingReportQuery.MonthlyOperatingSummary getMonthlySummary(LocalDate month);

    OperatingReportQuery.MonthlyOperatingSummary getThemeSummary(LocalDate month, String theme);

    AfterSalesMonthlySummary getAfterSalesSummary(LocalDate month);

    FreshnessView getFreshness();

    record AfterSalesMonthlySummary(
            LocalDate monthStart, int caseCount, int openCount, int completedCount) {
    }

    record FreshnessView(
            String versionBusinessId, Instant activatedAt, boolean late,
            List<FeedFreshness> feeds) {
    }

    record FeedFreshness(
            String feedCode, String status, String lastError, Instant updatedAt, long lagSeconds) {
    }
}
