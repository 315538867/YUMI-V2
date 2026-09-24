package com.yumi.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额与比例的精度基础方法（目录 FP-PROD-01、18、19）：业务公式共用同一舍入口径，
 * 业务模块、前端与 SQL 不再各自实现。金额 4 位 HALF_UP、比例 6 位 HALF_UP。
 */
public final class DecimalPolicy {

    private static final int MONEY_SCALE = 4;
    private static final int RATIO_SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DecimalPolicy() {
    }

    /** 金额、成本、单价统一 scale4 HALF_UP。 */
    public static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 比例统一 scale6 HALF_UP。 */
    public static BigDecimal ratio(BigDecimal value) {
        return value.setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 百分比文本 → 内部比例。 */
    public static BigDecimal percentToRatio(BigDecimal percent) {
        return percent.divide(HUNDRED, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 内部比例 → 百分比文本。 */
    public static BigDecimal ratioToPercent(BigDecimal ratio) {
        return ratio.multiply(HUNDRED).setScale(RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 取整（胶水克重等），HALF_UP。 */
    public static int wholeNumber(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
