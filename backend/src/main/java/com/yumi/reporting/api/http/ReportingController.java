package com.yumi.reporting.api.http;

import com.yumi.reporting.api.OperatingReportQuery.MonthlyOperatingSummary;
import com.yumi.reporting.api.ReportingQuery;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报表读取适配器（交付清单 R01）：经营总览、售后主题与数据时效。 */
@RestController
@RequestMapping("/api/reporting")
public class ReportingController {

    private final ReportingQuery reports;

    public ReportingController(ReportingQuery reports) {
        this.reports = reports;
    }

    private static LocalDate monthStart(String month) {
        return month.length() == 7
                ? YearMonth.parse(month).atDay(1)
                : LocalDate.parse(month).withDayOfMonth(1);
    }

    @GetMapping("/monthly-summary")
    public MonthlyOperatingSummary monthlySummary(@RequestParam String month) {
        return reports.getMonthlySummary(monthStart(month));
    }

    @GetMapping("/theme-summary")
    public MonthlyOperatingSummary themeSummary(
            @RequestParam String month, @RequestParam String theme) {
        return reports.getThemeSummary(monthStart(month), theme);
    }

    @GetMapping("/after-sales-summary")
    public ReportingQuery.AfterSalesMonthlySummary afterSalesSummary(@RequestParam String month) {
        return reports.getAfterSalesSummary(monthStart(month));
    }

    @GetMapping("/freshness")
    public ReportingQuery.FreshnessView freshness() {
        return reports.getFreshness();
    }
}
