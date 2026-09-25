package com.yumi.reports;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表应用服务（任务 9.1/9.2/9.4）。
 *
 * 口径：报表**只读**，全部来自不可变事实与投影；筛选与分页由服务端执行；
 * 每类报表使用**固定业务字段清单**（{@link ReportTypes}），因此导出天然不含物流字段；
 * 金额一律以字符串输出（scale4），历史资料使用快照列。
 * 一致性检查（9.4）只产出**失败证据**，不做任何静默覆盖。
 *
 * 本模块用只读 SQL 跨表取数，**不依赖任何业务模块的 Java 类型**（Modulith 视图下无出边）。
 */
@Service
public class ReportService {

    private static final int MAX_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;

    public ReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 查询（任务 9.1） ----------

    public ReportViews.ReportPage query(String typeCode, LocalDate dateFrom, LocalDate dateTo, Long orderId,
                                        Integer page, Integer size) {
        var type = requireType(typeCode);
        int pageNo = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, MAX_SIZE);
        var source = sourceOf(type.code());
        var args = new ArrayList<Object>();
        var where = whereClause(source, dateFrom, dateTo, orderId, args);
        long total = count(source.baseSql() + where, args);
        var rows = jdbcTemplate.queryForList(
                source.baseSql() + where + source.orderBy() + " LIMIT ? OFFSET ?",
                append(args, pageSize, (pageNo - 1) * pageSize));
        return new ReportViews.ReportPage(type.code(), type.name(), type.columns(), normalize(rows), pageNo,
                pageSize, total);
    }

    // ---------- 导出（任务 9.2） ----------

    /** 导出 CSV：表头与行都严格按固定业务字段清单，**不含物流公司、单号、运费与发货备注**。 */
    public String exportCsv(String typeCode, LocalDate dateFrom, LocalDate dateTo, Long orderId) {
        var type = requireType(typeCode);
        var source = sourceOf(type.code());
        var args = new ArrayList<Object>();
        var where = whereClause(source, dateFrom, dateTo, orderId, args);
        var rows = normalize(jdbcTemplate.queryForList(
                source.baseSql() + where + source.orderBy(), args.toArray()));
        var builder = new StringBuilder();
        builder.append(String.join(",", type.columns().stream().map(ReportTypes.Column::title).toList()))
                .append('\n');
        for (var row : rows) {
            builder.append(String.join(",", type.columns().stream()
                    .map(column -> csv(row.get(column.key()))).toList())).append('\n');
        }
        return builder.toString();
    }

    // ---------- 一致性检查（任务 9.4） ----------

    @Transactional(readOnly = true)
    public ReportViews.ConsistencyReport consistency() {
        var checks = new ArrayList<ReportViews.ConsistencyCheck>();
        checks.add(fulfillmentBalanceCheck());
        checks.add(inventoryBatchCheck());
        checks.add(afterSalesCheck());
        return new ReportViews.ConsistencyReport(checks,
                checks.stream().allMatch(ReportViews.ConsistencyCheck::consistent));
    }

    /**
     * 履约投影 vs 履约事实：各工序流入、可发货与累计发货必须等于事实汇总。
     *
     * 列对应关系（`domain-and-quantity-model.md` §6）：`making_inflow` 是**流入制作**的量，制作是首道工序、
     * 有效流入取订购数量而不落列，故该列恒为 0；`packing_inflow` = 流入捏毛装袋（库存接入 + 上游合格）；
     * `seam_inflow` = 流入缝边剪袋；`shippable_quantity` = 流入可发货（不含 `ORDER_DEMAND` 需求登记）− 发货消耗 + 作废回退。
     * 初版把三列各错位一个节点且漏查可发货，已在本任务修正（由 9.7 恢复演练的一致性核对发现）。
     */
    private ReportViews.ConsistencyCheck fulfillmentBalanceCheck() {
        var mismatches = jdbcTemplate.queryForList("""
                SELECT b.order_item_id, b.making_inflow, b.packing_inflow, b.seam_inflow,
                       b.shippable_quantity, b.shipped_quantity,
                       COALESCE(f.making, 0) fact_making, COALESCE(f.packing, 0) fact_packing,
                       COALESCE(f.seam, 0) fact_seam, COALESCE(f.shippable, 0) fact_shippable,
                       COALESCE(f.shipped, 0) fact_shipped
                FROM order_item_fulfillment_balances b
                LEFT JOIN (
                    -- 需求基线事实（ORDER_DEMAND 需求登记、ORDER_CHANGE 订单变更）不属于物理履约，
                    -- 与 FulfillmentRepository.countExecutionFacts 的口径一致，重建时必须排除。
                    SELECT order_item_id,
                           SUM(CASE WHEN node = 'MAKING' AND direction = 'IN' THEN quantity ELSE 0 END) making,
                           SUM(CASE WHEN node = 'PACKING_BAG' AND direction = 'IN' THEN quantity ELSE 0 END) packing,
                           SUM(CASE WHEN node = 'SEAM_CUTTING' AND direction = 'IN' THEN quantity ELSE 0 END) seam,
                           SUM(CASE WHEN node = 'SHIPPABLE' AND direction = 'IN'
                                     AND entry_type NOT IN ('ORDER_DEMAND', 'ORDER_CHANGE')
                                    THEN quantity ELSE 0 END)
                             - SUM(CASE WHEN node = 'SHIPPABLE' AND direction = 'OUT'
                                         AND entry_type NOT IN ('ORDER_DEMAND', 'ORDER_CHANGE')
                                        THEN quantity ELSE 0 END)
                             shippable,
                           SUM(CASE WHEN entry_type = 'SHIPMENT_CONSUME' THEN quantity
                                    WHEN entry_type = 'SHIPMENT_VOID' THEN -quantity ELSE 0 END) shipped
                    FROM fulfillment_entries GROUP BY order_item_id
                ) f ON f.order_item_id = b.order_item_id
                WHERE b.making_inflow <> COALESCE(f.making, 0)
                   OR b.packing_inflow <> COALESCE(f.packing, 0)
                   OR b.seam_inflow <> COALESCE(f.seam, 0)
                   OR b.shippable_quantity <> COALESCE(f.shippable, 0)
                   OR b.shipped_quantity <> COALESCE(f.shipped, 0)
                """);
        return new ReportViews.ConsistencyCheck("履约余额与履约事实一致", mismatches.isEmpty(),
                mismatches.isEmpty() ? "全部明细一致"
                        : "不一致明细 " + mismatches.stream()
                                .map(row -> String.valueOf(row.get("order_item_id"))).toList());
    }

    /** 库存批次当前数量 vs 该批次有效流水行汇总。 */
    private ReportViews.ConsistencyCheck inventoryBatchCheck() {
        var mismatches = jdbcTemplate.queryForList("""
                SELECT b.id, b.batch_no, b.quantity,
                       COALESCE(SUM(CASE WHEN l.direction = 'IN' THEN l.quantity ELSE -l.quantity END), 0) fact
                FROM inventory_batches b
                LEFT JOIN inventory_movement_lines l ON l.batch_id = b.id
                GROUP BY b.id, b.batch_no, b.quantity
                HAVING b.quantity <> COALESCE(SUM(CASE WHEN l.direction = 'IN' THEN l.quantity ELSE -l.quantity END), 0)
                """);
        return new ReportViews.ConsistencyCheck("库存批次数量与有效流水一致", mismatches.isEmpty(),
                mismatches.isEmpty() ? "全部批次一致"
                        : "不一致批次 " + mismatches.stream().map(row -> row.get("batch_no")).toList());
    }

    /** 售后已补发（台账消耗事实）vs 补发发货关联数量。 */
    private ReportViews.ConsistencyCheck afterSalesCheck() {
        var mismatches = jdbcTemplate.queryForList("""
                SELECT i.id, COALESCE(e.consumed, 0) consumed, COALESCE(l.linked, 0) linked
                FROM after_sales_items i
                LEFT JOIN (
                    SELECT after_sales_item_id, SUM(quantity) consumed FROM after_sales_fulfillment_entries
                    WHERE entry_type = 'REPLACEMENT_CONSUME' AND direction = 'OUT' GROUP BY after_sales_item_id
                ) e ON e.after_sales_item_id = i.id
                LEFT JOIN (
                    SELECT after_sales_item_id, SUM(quantity) linked FROM after_sales_shipment_links
                    GROUP BY after_sales_item_id
                ) l ON l.after_sales_item_id = i.id
                WHERE COALESCE(e.consumed, 0) <> COALESCE(l.linked, 0)
                """);
        return new ReportViews.ConsistencyCheck("售后已补发与补发发货关联一致", mismatches.isEmpty(),
                mismatches.isEmpty() ? "全部售后明细一致"
                        : "不一致售后明细 " + mismatches.stream().map(row -> row.get("id")).toList());
    }

    // ---------- 内部 ----------

    private record Source(String baseSql, String dateColumn, String orderColumn, String orderBy) {
    }

    /** 服务端筛选：日期区间（按类型的时间列）与订单（按类型的订单列）均可选。 */
    private static String whereClause(Source source, LocalDate from, LocalDate to, Long orderId,
                                      List<Object> args) {
        var clauses = new ArrayList<String>();
        if (from != null && source.dateColumn() != null) {
            clauses.add(source.dateColumn() + " >= ?");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null && source.dateColumn() != null) {
            clauses.add(source.dateColumn() + " <= ?");
            args.add(java.sql.Date.valueOf(to));
        }
        if (orderId != null && source.orderColumn() != null) {
            clauses.add(source.orderColumn() + " = ?");
            args.add(orderId);
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    /** 每类报表的只读查询：固定列清单（含确认快照列），不含物流字段。 */
    private Source sourceOf(String code) {
        return switch (code) {
            case ReportTypes.ORDER_FULFILLMENT -> new Source("""
                    SELECT o.order_no orderNo, o.customer_name customerName, o.order_date orderDate, o.status,
                           i.line_no lineNo, i.product_no productNo, i.product_name productName,
                           i.quantity, i.seam_quantity seamQuantity,
                           b.shipped_quantity shippedQuantity,
                           (i.quantity - b.shipped_quantity) undeliveredQuantity,
                           b.shippable_quantity shippableQuantity, o.receivable_amount receivableAmount
                    FROM orders o
                    JOIN order_items i ON i.order_id = o.id
                    LEFT JOIN order_item_fulfillment_balances b ON b.order_item_id = i.id
                    """, "o.order_date", "o.id", " ORDER BY o.id DESC, i.line_no");
            case ReportTypes.INVENTORY -> new Source("""
                    SELECT b.batch_no batchNo, b.product_no productNo, b.product_name productName, b.node,
                           b.seam_state seamState, b.quantity, b.source_type sourceType,
                           b.inventory_date inventoryDate
                    FROM inventory_batches b
                    """, "b.inventory_date", null, " ORDER BY b.id");
            case ReportTypes.PRODUCTION -> new Source("""
                    SELECT p.plan_no planNo, p.plan_type planType, o.order_no orderNo, i.line_no lineNo,
                           i.product_name productName, p.node, p.plan_date planDate,
                           p.employee_name employeeName, p.quantity, p.status,
                           v.completed_quantity completedQuantity, v.qualified_quantity qualifiedQuantity,
                           v.rework_quantity reworkQuantity, v.scrap_quantity scrapQuantity,
                           v.incomplete_quantity incompleteQuantity
                    FROM production_plans p
                    JOIN orders o ON o.id = p.order_id
                    JOIN order_items i ON i.id = p.order_item_id
                    LEFT JOIN production_verifications v ON v.plan_id = p.id
                    """, "p.plan_date", "p.order_id", " ORDER BY p.plan_date, p.id");
            case ReportTypes.SHIPMENT -> new Source("""
                    SELECT s.shipment_no shipmentNo, o.order_no orderNo, o.customer_name customerName,
                           s.status, s.shipment_date shipmentDate, si.line_no lineNo, si.product_no productNo,
                           si.product_name productName, si.quantity,
                           si.cumulative_shipped_quantity cumulativeShippedQuantity,
                           si.undelivered_quantity undeliveredQuantity
                    FROM shipments s
                    JOIN orders o ON o.id = s.order_id
                    JOIN shipment_items si ON si.shipment_id = s.id
                    """, "s.shipment_date", "s.order_id", " ORDER BY s.id DESC, si.line_no");
            case ReportTypes.SETTLEMENT -> new Source("""
                    SELECT o.order_no orderNo, o.customer_name customerName,
                           COALESCE(s.paid_amount, 0) paidAmount,
                           COALESCE(s.change_refund_amount, 0) changeRefundAmount,
                           COALESCE(s.net_settled_amount, 0) netSettledAmount,
                           COALESCE(s.after_sales_refund_amount, 0) afterSalesRefundAmount,
                           (COALESCE(s.net_settled_amount, 0) - COALESCE(s.after_sales_refund_amount, 0)) actualNetReceived,
                           COALESCE(s.refund_pending_amount, 0) refundPendingAmount,
                           COALESCE(s.effective_receivable_amount, o.receivable_amount) effectiveReceivableAmount
                    FROM orders o
                    LEFT JOIN order_settlement_balances s ON s.order_id = o.id
                    """, "o.order_date", "o.id", " ORDER BY o.id DESC");
            case ReportTypes.AFTER_SALES -> new Source("""
                    SELECT c.case_no caseNo, o.order_no orderNo, c.case_type caseType, c.status,
                           a.product_no productNo, a.product_name productName,
                           a.accepted_quantity acceptedQuantity, a.returned_quantity returnedQuantity,
                           v.rework_quantity reworkQuantity, v.scrap_quantity scrapQuantity,
                           a.replacement_required_quantity replacementRequiredQuantity,
                           COALESCE(e.consumed, 0) replacementShippedQuantity
                    FROM after_sales_cases c
                    JOIN orders o ON o.id = c.order_id
                    JOIN after_sales_items a ON a.case_id = c.id
                    LEFT JOIN after_sales_return_verifications v ON v.after_sales_item_id = a.id
                    LEFT JOIN (
                        SELECT after_sales_item_id, SUM(quantity) consumed FROM after_sales_fulfillment_entries
                        WHERE entry_type = 'REPLACEMENT_CONSUME' AND direction = 'OUT'
                        GROUP BY after_sales_item_id
                    ) e ON e.after_sales_item_id = a.id
                    """, "c.created_at", "c.order_id", " ORDER BY c.id DESC, a.id");
            default -> throw new ApiException(ErrorCode.REPORT_TYPE_INVALID);
        };
    }

    private long count(String sql, List<Object> args) {
        var value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (" + sql + ") t", Long.class,
                args.toArray());
        return value == null ? 0 : value;
    }

    private static Object[] append(List<Object> args, Object... extra) {
        var all = new ArrayList<>(args);
        java.util.Collections.addAll(all, extra);
        return all.toArray();
    }

    /** 金额与日期统一转字符串（金额 scale4），保证导出与接口口径一致。 */
    private static List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        var result = new ArrayList<Map<String, Object>>(rows.size());
        for (var row : rows) {
            var converted = new LinkedHashMap<String, Object>();
            row.forEach((key, value) -> {
                if (value instanceof BigDecimal money) {
                    converted.put(key, DecimalPolicy.money(money).toPlainString());
                } else if (value instanceof java.sql.Date date) {
                    converted.put(key, date.toLocalDate().toString());
                } else if (value instanceof java.sql.Timestamp timestamp) {
                    converted.put(key, timestamp.toLocalDateTime().toString());
                } else {
                    converted.put(key, value);
                }
            });
            result.add(converted);
        }
        return result;
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        var text = String.valueOf(value);
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text.replace("\"", "\"\"") + "\""
                : text;
    }

    private static ReportTypes.ReportType requireType(String code) {
        return ReportTypes.of(code).orElseThrow(() -> new ApiException(ErrorCode.REPORT_TYPE_INVALID,
                "报表类型不合法", List.of(new ApiFieldError("type",
                "仅支持 " + ReportTypes.all().stream().map(ReportTypes.ReportType::code).toList()))));
    }
}
