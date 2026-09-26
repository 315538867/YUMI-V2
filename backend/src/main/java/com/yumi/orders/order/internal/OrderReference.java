package com.yumi.orders.order.internal;

import com.yumi.calculation.DecimalPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 订单参考数据读取：客户默认收货信息、商品识别与成本快照、缝边种类名称与标准分钟、全局时薪。
 * 只读，不缓存；订单侧不复制商品/静态数据的计算逻辑。
 */
@Component
public class OrderReference {

    public record Customer(long id, String customerNo, String name, String contact, String phone,
                           String defaultRecipient, String defaultRecipientPhone,
                           String defaultRegion, String defaultAddress) {
    }

    /** 商品在订单侧的取数：识别信息 + 销售单价 + 单件成本组成快照（不缝边剪袋口径）+ 生产产能。 */
    public record Product(long id, String productNo, String name, String note, String status,
                          Long imageFileId, Long starLevelId, String starName, Integer starStdMinutes,
                          BigDecimal salePrice, BigDecimal totalCost, BigDecimal glueGrams, BigDecimal glueCost,
                          BigDecimal colorpasteCost, BigDecimal materialCost, BigDecimal productLaborFee,
                          BigDecimal packagingLaborFee, BigDecimal boxLaborFee, BigDecimal laborCost,
                          BigDecimal otherCost, Long defaultSeamTypeId, BigDecimal defaultSeamFee,
                          Integer packagingStdMinutes, int moldQuantity, int dailyBatchLimit) {
    }

    /** 缝边种类：只提供标准分钟；单件人工成本由全局时薪派生（FP-ORDER-04）。 */
    public record SeamType(long id, String name, int stdMinutes) {
    }

    private final JdbcTemplate jdbcTemplate;

    public OrderReference(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Customer> customer(long id) {
        var customer = jdbcTemplate.query("""
                SELECT id, customer_no, name, contact, phone, default_recipient, default_recipient_phone,
                       default_region, default_address
                FROM customers WHERE id = ?
                """, rs -> rs.next()
                ? new Customer(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9))
                : null, id);
        return Optional.ofNullable(customer);
    }

    public Optional<Product> product(long id) {
        var product = jdbcTemplate.query("""
                SELECT id, product_no, name, note, status, image_file_id, star_level_id, star_name,
                       star_std_minutes, sale_price, total_cost, glue_grams, glue_cost, colorpaste_cost,
                       material_cost, product_labor_fee, packaging_labor_fee, box_labor_fee, labor_cost,
                       other_cost, seam_type_id, seam_fee, packaging_std_minutes,
                       mold_quantity, daily_batch_limit
                FROM products WHERE id = ?
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            long imageId = rs.getLong("image_file_id");
            boolean imageNull = rs.wasNull();
            long starId = rs.getLong("star_level_id");
            boolean starNull = rs.wasNull();
            long seamId = rs.getLong("seam_type_id");
            boolean seamNull = rs.wasNull();
            return new Product(rs.getLong("id"), rs.getString("product_no"), rs.getString("name"),
                    rs.getString("note"), rs.getString("status"), imageNull ? null : imageId,
                    starNull ? null : starId, rs.getString("star_name"),
                    starNull ? null : rs.getInt("star_std_minutes"), rs.getBigDecimal("sale_price"),
                    rs.getBigDecimal("total_cost"),
                    rs.getBigDecimal("glue_grams"), rs.getBigDecimal("glue_cost"),
                    rs.getBigDecimal("colorpaste_cost"), rs.getBigDecimal("material_cost"),
                    rs.getBigDecimal("product_labor_fee"), rs.getBigDecimal("packaging_labor_fee"),
                    rs.getBigDecimal("box_labor_fee"), rs.getBigDecimal("labor_cost"),
                    rs.getBigDecimal("other_cost"), seamNull ? null : seamId, rs.getBigDecimal("seam_fee"),
                    nullableInt(rs.getObject("packaging_std_minutes")),
                    rs.getInt("mold_quantity"), rs.getInt("daily_batch_limit"));
        }, id);
        return Optional.ofNullable(product);
    }

    /** INT UNSIGNED 列经驱动 getObject 返回 Long，需按 Number 归一（可空）。 */
    private static Integer nullableInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    public Optional<SeamType> seamType(long id) {
        var type = jdbcTemplate.query(
                "SELECT id, name, std_minutes FROM seam_types WHERE id = ?",
                rs -> rs.next() ? new SeamType(rs.getLong(1), rs.getString(2), rs.getInt(3)) : null,
                id);
        return Optional.ofNullable(type);
    }

    /** 全局时薪（元/小时，scale4）：缝边单件人工成本的派生基数。 */
    public BigDecimal hourlyWage() {
        return moneySetting("hourly_wage");
    }

    /** 工作日小时数（小时/天，scale4）：确认快照冻结，生产域只读快照。 */
    public BigDecimal workdayHours() {
        return moneySetting("workday_hours");
    }

    /** 制品有效工时率（0–1 比例，scale6）：确认快照冻结，生产域只读快照。 */
    public BigDecimal makingEffectiveHourRate() {
        return DecimalPolicy.ratio(setting("making_effective_hour_rate"));
    }

    private BigDecimal moneySetting(String key) {
        return DecimalPolicy.money(setting(key));
    }

    private BigDecimal setting(String key) {
        var value = jdbcTemplate.query(
                "SELECT setting_value FROM catalog_settings WHERE setting_key = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, key);
        if (value == null) {
            throw new IllegalStateException("缺少目录设置项: " + key);
        }
        return value;
    }
}
