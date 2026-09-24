package com.yumi.catalog.product.internal;

import com.yumi.calculation.DecimalPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 计价参考数据读取：全局设置、星级对照、包装档位、缝边种类、文件存在性。
 * 单价与预填费用读 catalog_settings；星级/档位只提供标准分钟（提成由商品持有）；
 * 缝边种类只提供名称与成本单价（商品只保存默认值指针）。
 */
@Component
public class CatalogReference {

    public record StarLevel(long id, String name, int stdMinutes) {
    }

    public record PackagingTier(Long id, String tierName, BigDecimal stdMinutes) {
    }

    /** 缝边种类：商品只取名称与成本单价作订单缝边默认值与定价提示。 */
    public record SeamType(long id, String name, BigDecimal costPrice) {
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

    /** 金额类设置统一转 scale4（胶水单价、各项默认费用）。 */
    public BigDecimal moneySetting(String key) {
        return DecimalPolicy.money(setting(key));
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
                        ? new PackagingTier(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3))
                        : null,
                id);
        return Optional.ofNullable(tier);
    }

    public Optional<SeamType> seamType(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        var type = jdbcTemplate.query(
                "SELECT id, name, cost_price FROM seam_types WHERE id = ?",
                rs -> rs.next() ? new SeamType(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)) : null,
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
