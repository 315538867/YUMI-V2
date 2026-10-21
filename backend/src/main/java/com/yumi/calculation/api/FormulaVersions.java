package com.yumi.calculation.api;

/**
 * 公式规则版本与舍入版本常量。
 *
 * <p>版本是代码发布标识，不是数据库业务快照；调用方把当次返回的版本随业务结果保存，
 * 历史结果不因后续版本变化被回算覆盖（基线 07 §5）。
 */
public final class FormulaVersions {

    /** 商品单件预算公式代码。 */
    public static final String PRODUCT_BUDGET = "PRODUCT_BUDGET";

    /** 单日产能公式代码。 */
    public static final String DAILY_CAPACITY = "DAILY_CAPACITY";

    /** 个人工作量公式代码。 */
    public static final String INDIVIDUAL_WORKLOAD = "INDIVIDUAL_WORKLOAD";

    /** 小组工作量公式代码。 */
    public static final String TEAM_WORKLOAD = "TEAM_WORKLOAD";

    /** 金额分配公式代码。 */
    public static final String MONEY_ALLOCATION = "MONEY_ALLOCATION";

    /** 成本分配公式代码。 */
    public static final String COST_ALLOCATION = "COST_ALLOCATION";

    /** 来源金额公式代码。 */
    public static final String EXTENDED_AMOUNT = "EXTENDED_AMOUNT";

    /** 月度胶水减免公式代码。 */
    public static final String MONTHLY_GLUE_ALLOWANCE = "MONTHLY_GLUE_ALLOWANCE";

    /** 当前规则版本：公式语义变化时递增，并同步更新测试。 */
    public static final String RULE_VERSION = "2026.10-1";

    /** 成本舍入版本：4 位小数、HALF_UP。 */
    public static final String ROUNDING_COST_4 = "HALF_UP-4";

    /** 结算舍入版本：2 位小数、HALF_UP。 */
    public static final String ROUNDING_SETTLEMENT_2 = "HALF_UP-2";

    /** 秒级整数舍入版本：向上取整到整秒。 */
    public static final String ROUNDING_SECONDS_CEILING = "CEILING-1s";

    private FormulaVersions() {
    }
}
