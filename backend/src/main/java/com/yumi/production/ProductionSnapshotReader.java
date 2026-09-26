package com.yumi.production;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 生产参数快照只读入口（阶段五 5.1）：生产域的历史参数一律取自订单确认快照，
 * 不读取当前商品或静态数据，商品与全局设置变更不回溯既有订单与既有生产任务。
 */
@Component
public class ProductionSnapshotReader {

    /** 订单确认时冻结的生产参数；{@code dailyMaxCapacity} 由模具数量与每日批次数派生。 */
    public record ProductionSnapshot(long orderId, long orderItemId, long productId, String productNo,
                                     String productName, Integer starStdMinutes, Integer packagingStdMinutes,
                                     Integer seamStdMinutes, BigDecimal makingEffectiveHourRate,
                                     BigDecimal workdayHours, int moldQuantity, int dailyBatchLimit) {

        public int dailyMaxCapacity() {
            return moldQuantity * dailyBatchLimit;
        }

        /** 该工序的单件标准分钟；无对应配置时为 null（不伪造 0）。 */
        public Integer standardMinutes(String node) {
            return switch (node) {
                case ProductionNodes.MAKING -> starStdMinutes;
                case ProductionNodes.PACKING_BAG -> packagingStdMinutes;
                case ProductionNodes.SEAM_CUTTING -> seamStdMinutes;
                default -> null;
            };
        }
    }

    private static final String COLUMNS = """
            order_id, order_item_id, product_id, product_no, product_name, star_std_minutes,
            packaging_std_minutes, seam_std_minutes, making_effective_hour_rate, workday_hours,
            mold_quantity, daily_batch_limit
            """;

    private final JdbcTemplate jdbcTemplate;

    public ProductionSnapshotReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ProductionSnapshot> byOrderItem(long orderItemId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM order_item_snapshots WHERE order_item_id = ?",
                ProductionSnapshotReader::map, orderItemId).stream().findFirst();
    }

    public List<ProductionSnapshot> byOrderItems(Collection<Long> orderItemIds) {
        if (orderItemIds == null || orderItemIds.isEmpty()) {
            return List.of();
        }
        var placeholders = String.join(", ", java.util.Collections.nCopies(orderItemIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM order_item_snapshots WHERE order_item_id IN (" + placeholders
                        + ") ORDER BY order_item_id",
                ProductionSnapshotReader::map, orderItemIds.toArray());
    }

    private static ProductionSnapshot map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductionSnapshot(rs.getLong("order_id"), rs.getLong("order_item_id"),
                rs.getLong("product_id"), rs.getString("product_no"), rs.getString("product_name"),
                nullableInt(rs.getObject("star_std_minutes")), nullableInt(rs.getObject("packaging_std_minutes")),
                nullableInt(rs.getObject("seam_std_minutes")), rs.getBigDecimal("making_effective_hour_rate"),
                rs.getBigDecimal("workday_hours"), rs.getInt("mold_quantity"), rs.getInt("daily_batch_limit"));
    }

    /** INT UNSIGNED 列经驱动 getObject 返回 Long，需按 Number 归一（可空）。 */
    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
