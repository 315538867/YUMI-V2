package com.yumi.catalog.settings;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.calculation.FormulaCatalog;
import com.yumi.catalog.changelog.MasterDataChangeLogService;
import com.yumi.catalog.settings.SettingsViews.StarLevelView;
import com.yumi.catalog.settings.SettingsViews.TierView;
import com.yumi.catalog.settings.SettingsViews.ValuesView;
import com.yumi.identity.AuditContext;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 全局设置服务：values（catalog_settings）、星级时长（star_levels）、包装档位（packaging_tiers）。
 * 全局只存参考值；商品保存时冻结快照，任何全局变更都不回溯既有商品。
 */
@Service
@Transactional
public class SettingsService {

    public static final Map<String, String> MONEY_KEYS = Map.of(
            "glueUnitPrice", "glue_unit_price",
            "colorpasteUnitPrice", "colorpaste_unit_price",
            "boxLaborDefault", "box_labor_default",
            "transportPackingDefault", "transport_packing_default",
            "sundriesDefault", "sundries_default",
            "rentUtilitiesDefault", "rent_utilities_default",
            "packagingCommissionDefault", "packaging_commission_default",
            "hourlyWage", "hourly_wage",
            "workdayHours", "workday_hours");
    public static final String LOSS_KEY = "lossRateDefault";
    private static final String LOSS_SETTING = "loss_rate_default";
    public static final String HOUR_RATE_KEY = "makingEffectiveHourRate";
    private static final String HOUR_RATE_SETTING = "making_effective_hour_rate";
    public static final String MARGIN_KEY = "targetMarginRate";
    private static final String MARGIN_SETTING = "target_margin_rate";

    private final JdbcTemplate jdbcTemplate;
    private final AuditContext auditContext;
    private final MasterDataChangeLogService changeLogService;

    public SettingsService(JdbcTemplate jdbcTemplate, AuditContext auditContext,
                           MasterDataChangeLogService changeLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditContext = auditContext;
        this.changeLogService = changeLogService;
    }

    /** 只读公式说明（任务 2.20）：从 calculation 模块的静态目录按业务分类分组返回，不读取数据库。 */
    @Transactional(readOnly = true)
    public SettingsViews.FormulaCatalogView formulaCatalog() {
        var groups = new LinkedHashMap<String, List<SettingsViews.FormulaView>>();
        for (var entry : FormulaCatalog.entries()) {
            groups.computeIfAbsent(entry.category(), category -> new ArrayList<>())
                    .add(new SettingsViews.FormulaView(entry.identifier(), entry.name(), entry.inputs(),
                            entry.expression(), entry.rounding(), entry.resultMeaning(), entry.example(),
                            entry.codeLocation(), entry.testId()));
        }
        return new SettingsViews.FormulaCatalogView(groups.entrySet().stream()
                .map(group -> new SettingsViews.FormulaGroupView(group.getKey(), group.getValue()))
                .toList());
    }

    public SettingsViews.SettingsView view() {
        var byKey = new LinkedHashMap<String, BigDecimal>();
        jdbcTemplate.query("SELECT setting_key, setting_value FROM catalog_settings",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    byKey.put(rs.getString("setting_key"), rs.getBigDecimal("setting_value"));
                });
        var values = new ValuesView(
                money(byKey, "glue_unit_price"),
                money(byKey, "colorpaste_unit_price"),
                percent6(byKey, LOSS_SETTING),
                money(byKey, "box_labor_default"),
                money(byKey, "transport_packing_default"),
                money(byKey, "sundries_default"),
                money(byKey, "rent_utilities_default"),
                money(byKey, "packaging_commission_default"),
                money(byKey, "hourly_wage"),
                money(byKey, "workday_hours"),
                ratio6(byKey, HOUR_RATE_SETTING),
                percent6(byKey, MARGIN_SETTING));
        return new SettingsViews.SettingsView(values, starLevels(), tiers());
    }

    public ValuesView patchValues(Map<String, String> patch) {
        var reason = patch.get("reason");
        var data = new LinkedHashMap<String, Object>();
        var updates = new LinkedHashMap<String, String>();
        for (var entry : patch.entrySet()) {
            var key = entry.getKey();
            if ("reason".equals(key)) {
                continue;
            }
            var column = MONEY_KEYS.get(key);
            var isLoss = LOSS_KEY.equals(key);
            var isHourRate = HOUR_RATE_KEY.equals(key);
            var isMargin = MARGIN_KEY.equals(key);
            if (column == null && !isLoss && !isHourRate && !isMargin) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "未知设置项：" + key,
                        List.of(new ApiFieldError(key, "未知设置项")));
            }
            BigDecimal parsed;
            try {
                parsed = new BigDecimal(entry.getValue().trim());
            } catch (NumberFormatException bad) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "设置值不是合法数字：" + key,
                        List.of(new ApiFieldError(key, "不是合法数字")));
            }
            if (parsed.signum() < 0) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "设置值不得为负：" + key,
                        List.of(new ApiFieldError(key, "不得为负")));
            }
            if (isHourRate && (parsed.signum() == 0 || parsed.compareTo(BigDecimal.ONE) > 0)) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "制品有效工时率必须在 0 与 1 之间",
                        List.of(new ApiFieldError(key, "必须大于 0 且不超过 1")));
            }
            if (isMargin && parsed.compareTo(new BigDecimal("100")) >= 0) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "目标利润率必须小于 100%",
                        List.of(new ApiFieldError(key, "必须小于 100")));
            }
            var isRatio = isLoss || isHourRate;
            var normalized = (isRatio ? DecimalPolicy.ratio(parsed) : DecimalPolicy.money(parsed)).toPlainString();
            updates.put(isLoss ? LOSS_SETTING : isHourRate ? HOUR_RATE_SETTING
                    : isMargin ? MARGIN_SETTING : column, normalized);
            data.put(key, normalized);
        }
        if (updates.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "没有可更新的设置项",
                    List.of(new ApiFieldError("settings", "没有可更新的设置项")));
        }
        var before = readValuesSnapshot(updates.keySet());
        for (var entry : updates.entrySet()) {
            jdbcTemplate.update(
                    "UPDATE catalog_settings SET setting_value = ?, version = version + 1, "
                            + "updated_at = UTC_TIMESTAMP(6) WHERE setting_key = ?",
                    new BigDecimal(entry.getValue()), entry.getKey());
        }
        var audit = auditContext.current();
        changeLogService.record("SETTINGS", 0L, "GLOBAL_SETTINGS", before, data, reason,
                audit.adminUsername(), audit.requestId());
        return view().values();
    }

    private void validateStdMinutes(int stdMinutes) {
        if (stdMinutes <= 0 || stdMinutes > 360) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "标准时长必须在 1-360 分钟",
                    List.of(new ApiFieldError("stdMinutes", "必须在 1-360 分钟")));
        }
    }

    private StarLevelView starViewById(long id) {
        var view = jdbcTemplate.query(
                "SELECT id, name, std_minutes FROM star_levels WHERE id = ?",
                (rs, rowNum) -> new StarLevelView(
                        rs.getLong("id"), rs.getString("name"), rs.getInt("std_minutes")),
                id);
        if (view == null || view.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "星级条目不存在");
        }
        return view.get(0);
    }

    public List<TierView> tiers() {
        return jdbcTemplate.query(
                "SELECT id, tier_name, std_minutes FROM packaging_tiers ORDER BY id",
                (rs, rowNum) -> new TierView(
                        rs.getLong("id"),
                        rs.getString("tier_name"),
                        String.valueOf(rs.getInt("std_minutes"))));
    }

    public List<StarLevelView> starLevels() {
        return jdbcTemplate.query(
                "SELECT id, name, std_minutes FROM star_levels ORDER BY id",
                (rs, rowNum) -> new StarLevelView(
                        rs.getLong("id"), rs.getString("name"), rs.getInt("std_minutes")));
    }

    private Map<String, Object> readValuesSnapshot(Iterable<String> columns) {
        var before = new LinkedHashMap<String, Object>();
        for (var column : columns) {
            var value = jdbcTemplate.queryForObject(
                    "SELECT setting_value FROM catalog_settings WHERE setting_key = ?",
                    BigDecimal.class, column);
            before.put(column, value == null ? null : value.toPlainString());
        }
        return before;
    }

    private String money(Map<String, BigDecimal> byKey, String key) {
        var value = byKey.get(key);
        return value == null ? "0.0000" : DecimalPolicy.money(value).toPlainString();
    }

    private String percent6(Map<String, BigDecimal> byKey, String key) {
        var value = byKey.get(key);
        return value == null ? "0.000000" : DecimalPolicy.ratio(value).toPlainString();
    }

    private String ratio6(Map<String, BigDecimal> byKey, String key) {
        var value = byKey.get(key);
        return value == null ? "0.000000" : DecimalPolicy.ratio(value).toPlainString();
    }
}
