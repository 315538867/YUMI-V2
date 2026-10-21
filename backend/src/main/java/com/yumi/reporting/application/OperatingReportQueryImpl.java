package com.yumi.reporting.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.reporting.api.OperatingReportQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 月度经营汇总实现：只读报表投影记录，不跨模块查询业务表。
 */
public class OperatingReportQueryImpl implements OperatingReportQuery {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorization;

    public OperatingReportQueryImpl(
            JdbcClient jdbc, ObjectMapper objectMapper, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
    }

    @Override
    public MonthlyOperatingSummary getMonthlySummary(LocalDate month) {
        authorization.require(PermissionCodes.REPORTING_READ, ResourceScope.GLOBAL);
        if (month == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REPORT_MONTH", "报表月份不能为空");
        }
        LocalDate monthStart = month.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        Accumulator acc = new Accumulator();
        for (JsonNode item : records("ORDER")) {
            LocalDate date = localDate(item, "orderDate");
            if (!inMonth(date, monthStart, monthEnd)) {
                continue;
            }
            acc.orderCount++;
            acc.orderReceivable = acc.orderReceivable.add(decimal(item, "currentReceivable"));
        }
        for (JsonNode item : records("PRODUCTION")) {
            Instant updated = instant(item, "updatedAt");
            if (updated == null || !inMonth(updated, monthStart)) {
                continue;
            }
            acc.productionRequired += integer(item, "requiredQuantity");
            acc.productionFulfilled += integer(item, "fulfilledQuantity");
            acc.productionGap += integer(item, "fulfillmentGapQuantity");
            acc.inSystem += integer(item, "inSystemQuantity");
            acc.consumed += integer(item, "consumedQuantity");
            acc.destroyed += integer(item, "destroyedQuantity");
            acc.offline += integer(item, "offlineInventoryQuantity");
        }
        boolean hasCost = false;
        for (JsonNode item : records("COST")) {
            LocalDate date = localDate(item, "businessDate");
            if (!inMonth(date, monthStart, monthEnd)) {
                continue;
            }
            hasCost = true;
            acc.costTotal = acc.costTotal.add(decimal(item, "amount"));
        }
        for (JsonNode item : records("PAYROLL")) {
            LocalDate periodStart = localDate(item, "periodStartDate");
            if (!inMonth(periodStart, monthStart, monthEnd)) {
                continue;
            }
            acc.payrollPayable = acc.payrollPayable.add(decimal(item, "payableAmount"));
            acc.payrollPaid = acc.payrollPaid.add(decimal(item, "paidAmount"));
        }
        for (JsonNode item : records("CASH")) {
            if (!item.path("valid").asBoolean(true)) {
                continue;
            }
            LocalDate date = localDate(item, "businessDate");
            if (!inMonth(date, monthStart, monthEnd)) {
                continue;
            }
            String direction = item.path("direction").asText();
            String type = item.path("cashType").asText();
            BigDecimal amount = decimal(item, "amount");
            if ("IN".equals(direction)) {
                acc.cashInflow = acc.cashInflow.add(amount);
            } else {
                acc.cashOutflow = acc.cashOutflow.add(amount);
            }
            switch (type) {
                case "ORDER_RECEIPT" -> acc.orderReceipts = acc.orderReceipts.add(amount);
                case "ORDER_REFUND" -> acc.orderRefunds = acc.orderRefunds.add(amount);
                case "AFTER_SALES_REFUND", "AFTER_SALES_COMPENSATION" ->
                        acc.afterSalesPayments = acc.afterSalesPayments.add(amount);
                case "PAYROLL_RETURN" -> acc.payrollCashReturn = acc.payrollCashReturn.add(amount);
                case "DAILY_INCOME" -> acc.operatingIncome = acc.operatingIncome.add(amount);
                case "DAILY_EXPENSE" -> acc.operatingExpense = acc.operatingExpense.add(amount);
                default -> {
                    // 工资付款、冲销等保留在总现金流，不计入经营收入或支出
                }
            }
        }
        boolean late = jdbc.sql("""
                        SELECT COUNT(*) FROM reporting_feed_cursors WHERE status = 'FAILED'
                        """).query(Long.class).single() > 0;
        return new MonthlyOperatingSummary(
                monthStart, acc.orderCount, acc.orderReceivable, acc.productionRequired,
                acc.productionFulfilled, acc.productionGap, acc.inSystem, acc.consumed,
                acc.destroyed, acc.offline, acc.costTotal, acc.orderCount > 0 && !hasCost,
                acc.payrollPayable, acc.payrollPaid, acc.payrollCashReturn, acc.operatingIncome,
                acc.operatingExpense, acc.orderReceipts, acc.orderRefunds,
                acc.orderReceipts.subtract(acc.orderRefunds), acc.afterSalesPayments,
                acc.cashInflow, acc.cashOutflow, acc.cashInflow.subtract(acc.cashOutflow), late);
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

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull()
                ? BigDecimal.ZERO : new BigDecimal(value.asText("0"));
    }

    private static int integer(JsonNode node, String field) {
        return node.path(field).asInt(0);
    }

    private static LocalDate localDate(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : LocalDate.parse(value.asText());
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null : Instant.parse(value.asText());
    }

    private static boolean inMonth(Instant value, LocalDate monthStart) {
        if (value == null) {
            return false;
        }
        LocalDate date = LocalDate.ofInstant(value, ZoneOffset.UTC);
        return !date.isBefore(monthStart) && !date.isAfter(
                monthStart.withDayOfMonth(monthStart.lengthOfMonth()));
    }

    private static boolean inMonth(LocalDate value, LocalDate monthStart, LocalDate monthEnd) {
        return value != null && !value.isBefore(monthStart) && !value.isAfter(monthEnd);
    }

    private static final class Accumulator {
        private int orderCount;
        private BigDecimal orderReceivable = BigDecimal.ZERO;
        private int productionRequired;
        private int productionFulfilled;
        private int productionGap;
        private int inSystem;
        private int consumed;
        private int destroyed;
        private int offline;
        private BigDecimal costTotal = BigDecimal.ZERO;
        private BigDecimal payrollPayable = BigDecimal.ZERO;
        private BigDecimal payrollPaid = BigDecimal.ZERO;
        private BigDecimal payrollCashReturn = BigDecimal.ZERO;
        private BigDecimal operatingIncome = BigDecimal.ZERO;
        private BigDecimal operatingExpense = BigDecimal.ZERO;
        private BigDecimal orderReceipts = BigDecimal.ZERO;
        private BigDecimal orderRefunds = BigDecimal.ZERO;
        private BigDecimal afterSalesPayments = BigDecimal.ZERO;
        private BigDecimal cashInflow = BigDecimal.ZERO;
        private BigDecimal cashOutflow = BigDecimal.ZERO;
    }
}
