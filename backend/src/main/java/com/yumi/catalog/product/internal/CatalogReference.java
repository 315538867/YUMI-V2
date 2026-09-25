package com.yumi.catalog.product.internal;

import com.yumi.calculation.DecimalPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 计价参考数据读取：全局设置、星级对照、包装档位、缝边种类、文件存在性。
 * 单价、时薪、制品有效工时率与预填费用读 catalog_settings；
 * 星级/包装档位/缝边种类都只提供「标准分钟」（提成由商品持有），人工费一律由全局时薪派生。
 */
@Component
public class CatalogReference {

    public record StarLevel(long id, String name, int stdMinutes) {
    }

    public record PackagingTier(Long id, String tierName, int stdMinutes) {
    }

    /** 缝边种类：与星级、档位同构，只提供标准分钟；单件人工成本 = 分钟 × 全局时薪 ÷ 60。 */
    public record SeamType(long id, String name, int stdMinutes) {
    }

    private final JdbcTemplate jdbcTemplate;

    public CatalogReference(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 全局设置值（DECIMAL(19,6)），缺失时抛出配置错误。 */
    public BigDecimal setting(String key) {
        var value = jdbcTemplate.query(
                "SELECT setting_value FROM catalog_settings WHERE setting_key = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, key);
        if (value == null) {
            throw new IllegalStateException("缺少目录设置项: " + key);
        }
        return value;
    }

    /** 金额类设置统一转 scale4（胶水单价、时薪、各项默认费用）。 */
    public BigDecimal moneySetting(String key) {
        return DecimalPolicy.money(setting(key));
    }

    /** 全局时薪（元/小时，scale4）。 */
    public BigDecimal hourlyWage() {
        return moneySetting("hourly_wage");
    }

    /** 工作日小时数（小时/天，scale4）：制品日薪与工作日标准数量的基数。 */
    public BigDecimal workdayHours() {
        return moneySetting("workday_hours");
    }

    /** 制品有效工时率（0–1 比例，scale6）。 */
    public BigDecimal makingEffectiveHourRate() {
        return DecimalPolicy.ratio(setting("making_effective_hour_rate"));
    }

    public Optional<StarLevel> starLevelById(long id) {
        var level = jdbcTemplate.query(
                "SELECT id, name, std_minutes FROM star_levels WHERE id = ?",
                rs -> rs.next() ? new StarLevel(rs.getLong(1), rs.getString(2), rs.getInt(3)) : null,
                id);
        return Optional.ofNullable(level);
    }

    public Optional<PackagingTier> packagingTier(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        var tier = jdbcTemplate.query(
                "SELECT id, tier_name, std_minutes FROM packaging_tiers WHERE id = ?",
                rs -> rs.next()
                        ? new PackagingTier(rs.getLong(1), rs.getString(2), rs.getInt(3))
                        : null,
                id);
        return Optional.ofNullable(tier);
    }

    public Optional<SeamType> seamType(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        var type = jdbcTemplate.query(
                "SELECT id, name, std_minutes FROM seam_types WHERE id = ?",
                rs -> rs.next() ? new SeamType(rs.getLong(1), rs.getString(2), rs.getInt(3)) : null,
                id);
        return Optional.ofNullable(type);
    }

    public boolean fileExists(Long fileId) {
        if (fileId == null) {
            return false;
        }
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_metadata WHERE id = ?", Integer.class, fileId);
        return count != null && count > 0;
    }
}
