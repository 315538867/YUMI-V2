package com.yumi.platform.api.value;

/**
 * 整数分钟：商品及每日有效时间标准。
 *
 * <p>输入边界转换为秒后再参与公式，录入和快照保留分钟。
 */
public record DurationMinutes(int value) {

    public static final DurationMinutes ZERO = new DurationMinutes(0);

    public DurationMinutes {
        if (value < 0) {
            throw new IllegalArgumentException("分钟数不允许为负：" + value);
        }
    }

    public static DurationMinutes of(int value) {
        return new DurationMinutes(value);
    }

    public DurationSeconds toSeconds() {
        return DurationSeconds.of(Math.multiplyExact((long) value, 60L));
    }
}
