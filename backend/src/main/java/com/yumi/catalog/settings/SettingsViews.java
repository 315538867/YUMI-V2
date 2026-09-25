package com.yumi.catalog.settings;


import java.math.BigDecimal;
import java.util.List;

public final class SettingsViews {

    private SettingsViews() {
    }

    public record ValuesView(String glueUnitPrice, String colorpasteUnitPrice, String lossRateDefault,
                             String boxLaborDefault, String transportPackingDefault,
                             String sundriesDefault, String rentUtilitiesDefault,
                             String packagingCommissionDefault, String hourlyWage,
                             String workdayHours, String makingEffectiveHourRate) {
    }

    public record StarLevelView(long id, String name, int stdMinutes) {
    }

    public record TierView(long id, String tierName, String stdMinutes) {
    }

    public record SettingsView(ValuesView values, List<StarLevelView> starLevels,
                               List<TierView> packagingTiers) {
    }

    /** 只读公式说明：按业务分类分组，字段与公式目录一一对应。 */
    public record FormulaView(String identifier, String name, String inputs, String expression,
                              String rounding, String resultMeaning, String example,
                              String codeLocation, String testId) {
    }

    public record FormulaGroupView(String category, List<FormulaView> formulas) {
    }

    public record FormulaCatalogView(List<FormulaGroupView> groups) {
    }
}
