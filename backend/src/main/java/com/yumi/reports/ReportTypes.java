package com.yumi.reports;

import java.util.List;

/**
 * 报表类型目录（任务 9.1/9.2）：六类台账，每类固定**业务字段清单**与列标题。
 * 导出（`/export`）复用同一清单，因此导出**天然不含物流公司、单号、运费与发货备注**（物流字段只在发货详情/打印显示）。
 */
public final class ReportTypes {

    /** 一类报表：类型码、中文名、固定列清单。 */
    public record ReportType(String code, String name, List<Column> columns) {
    }

    /** 一列：键与中文标题。 */
    public record Column(String key, String title) {
    }

    public static final String ORDER_FULFILLMENT = "ORDER_FULFILLMENT";
    public static final String INVENTORY = "INVENTORY";
    public static final String PRODUCTION = "PRODUCTION";
    public static final String SHIPMENT = "SHIPMENT";
    public static final String SETTLEMENT = "SETTLEMENT";
    public static final String AFTER_SALES = "AFTER_SALES";

    private static final List<ReportType> ALL = List.of(
            new ReportType(ORDER_FULFILLMENT, "订单履约台账", List.of(
                    new Column("orderNo", "订单编号"),
                    new Column("customerName", "客户"),
                    new Column("orderDate", "下单日期"),
                    new Column("status", "主状态"),
                    new Column("lineNo", "明细序号"),
                    new Column("productNo", "商品编号"),
                    new Column("productName", "商品名称"),
                    new Column("quantity", "当前有效数量"),
                    new Column("seamQuantity", "缝边数量"),
                    new Column("shippedQuantity", "累计有效发货"),
                    new Column("undeliveredQuantity", "未交付需求"),
                    new Column("shippableQuantity", "当前可发货"),
                    new Column("receivableAmount", "当前有效应收"))),
            new ReportType(INVENTORY, "库存台账", List.of(
                    new Column("batchNo", "批次编号"),
                    new Column("productNo", "商品编号"),
                    new Column("productName", "商品名称"),
                    new Column("node", "已完成工序"),
                    new Column("seamState", "缝边状态"),
                    new Column("quantity", "当前数量"),
                    new Column("sourceType", "来源业务"),
                    new Column("inventoryDate", "库存日期"))),
            new ReportType(PRODUCTION, "生产台账", List.of(
                    new Column("planNo", "计划编号"),
                    new Column("planType", "计划类型"),
                    new Column("orderNo", "订单编号"),
                    new Column("lineNo", "明细序号"),
                    new Column("productName", "商品名称"),
                    new Column("node", "工序"),
                    new Column("planDate", "计划日期"),
                    new Column("employeeName", "执行员工"),
                    new Column("quantity", "计划数量"),
                    new Column("status", "计划状态"),
                    new Column("completedQuantity", "本次完成"),
                    new Column("qualifiedQuantity", "合格"),
                    new Column("reworkQuantity", "返工"),
                    new Column("scrapQuantity", "报废"),
                    new Column("incompleteQuantity", "未完成"))),
            // 发货台账固定业务字段：**不含物流公司、单号、运费与发货备注**
            new ReportType(SHIPMENT, "发货台账", List.of(
                    new Column("shipmentNo", "批次编号"),
                    new Column("orderNo", "订单编号"),
                    new Column("customerName", "客户"),
                    new Column("status", "批次状态"),
                    new Column("shipmentDate", "发货日期"),
                    new Column("lineNo", "明细序号"),
                    new Column("productNo", "商品编号"),
                    new Column("productName", "商品名称"),
                    new Column("quantity", "本次数量"),
                    new Column("cumulativeShippedQuantity", "累计发货快照"),
                    new Column("undeliveredQuantity", "未交付快照"))),
            new ReportType(SETTLEMENT, "收退款台账", List.of(
                    new Column("orderNo", "订单编号"),
                    new Column("customerName", "客户"),
                    new Column("paidAmount", "累计订单收款"),
                    new Column("changeRefundAmount", "累计订单变更退款"),
                    new Column("netSettledAmount", "订单结清净额"),
                    new Column("afterSalesRefundAmount", "售后退款（单列）"),
                    new Column("actualNetReceived", "累计实际净收"),
                    new Column("refundPendingAmount", "订单待退款"),
                    new Column("effectiveReceivableAmount", "当前有效应收"))),
            new ReportType(AFTER_SALES, "售后台账", List.of(
                    new Column("caseNo", "售后单号"),
                    new Column("orderNo", "订单编号"),
                    new Column("caseType", "售后类型"),
                    new Column("status", "售后状态"),
                    new Column("productNo", "商品编号"),
                    new Column("productName", "商品名称"),
                    new Column("acceptedQuantity", "受理数量"),
                    new Column("returnedQuantity", "退回数量"),
                    new Column("reworkQuantity", "售后返工"),
                    new Column("scrapQuantity", "售后报废"),
                    new Column("replacementRequiredQuantity", "补发需求"),
                    new Column("replacementShippedQuantity", "已补发"))));

    private ReportTypes() {
    }

    public static List<ReportType> all() {
        return ALL;
    }

    /** 按类型码解析；未知类型返回 empty（由服务层映射为 REPORT_TYPE_INVALID）。 */
    public static java.util.Optional<ReportType> of(String code) {
        if (code == null) {
            return java.util.Optional.empty();
        }
        return ALL.stream().filter(type -> type.code().equalsIgnoreCase(code.trim())).findFirst();
    }
}
