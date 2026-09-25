package com.yumi.production.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 生产模块对售后侧的只读引用（任务 8.4/8.5）：读取售后明细、退回核验与售后补发台账余额。
 * 与 {@link OrderProductionReference} 同风格——跨模块只读字段，**不依赖 orders 模块的 Java 类型**，
 * 也不复制售后业务规则（写回售后台账一律走 `orders.ledger.AfterSalesLedger`）。
 */
@Component
public class AfterSalesProductionReference {

    /**
     * 售后明细上下文：受理数量、退回核验的返工/报废数量、补发需求与台账余额。
     * `replacementRequiredQuantity` 为补发需求，`shippedQuantity` 为已补发，`availableQuantity` 为可补发。
     */
    public record AfterSalesItemContext(long afterSalesItemId, long caseId, long orderId, long orderItemId,
                                        String productNo, String productName, int acceptedQuantity,
                                        int returnedQuantity, int reworkQuantity, int scrapQuantity,
                                        int replacementRequiredQuantity, int availableQuantity,
                                        int shippedQuantity) {

        /** 补发生产来源额度：补发需求 − 已补发 − 可补发（库存/生产已形成的部分不再重复排产）。 */
        public int replacementBalance() {
            return Math.max(0, replacementRequiredQuantity - shippedQuantity - availableQuantity);
        }

        /** 售后返工来源额度：退回核验的返工数量（未核验退回则为 0）。 */
        public int reworkBalance() {
            return Math.max(0, reworkQuantity);
        }
    }

    private static final String SQL = """
            SELECT i.id, i.case_id, i.order_id, i.order_item_id, i.product_no, i.product_name,
                   i.accepted_quantity, i.returned_quantity,
                   COALESCE(v.rework_quantity, 0) rework_quantity,
                   COALESCE(v.scrap_quantity, 0) scrap_quantity,
                   i.replacement_required_quantity,                   COALESCE(e.available_quantity, 0) available_quantity,
                   COALESCE(e.shipped_quantity, 0) shipped_quantity
            FROM after_sales_items i
            LEFT JOIN after_sales_return_verifications v ON v.after_sales_item_id = i.id
            LEFT JOIN (
                SELECT after_sales_item_id,
                       SUM(CASE WHEN direction = 'IN' THEN quantity ELSE -quantity END) available_quantity,
                       SUM(CASE WHEN entry_type = 'REPLACEMENT_CONSUME' AND direction = 'OUT'
                                THEN quantity ELSE 0 END) shipped_quantity
                FROM after_sales_fulfillment_entries GROUP BY after_sales_item_id
            ) e ON e.after_sales_item_id = i.id
            WHERE i.id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public AfterSalesProductionReference(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AfterSalesItemContext> item(long afterSalesItemId) {
        return query(SQL, afterSalesItemId);
    }

    /**
     * 锁定读售后明细（含台账余额）：从售后来源创建计划前先锁明细行，
     * 使「来源行按需创建 + 安排额度」串行，避免并发各自读到未安排的余额而超支。
     */
    public Optional<AfterSalesItemContext> itemForUpdate(long afterSalesItemId) {
        return query(SQL.replace("WHERE i.id = ?", "WHERE i.id = ? FOR UPDATE"), afterSalesItemId);
    }

    private Optional<AfterSalesItemContext> query(String sql, long afterSalesItemId) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AfterSalesItemContext(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                rs.getInt(13)), afterSalesItemId).stream().findFirst();
    }
}
