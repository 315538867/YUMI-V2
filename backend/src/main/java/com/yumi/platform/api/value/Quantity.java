package com.yumi.platform.api.value;

/**
 * 正式数量，对应 {@code BIGINT} 非负整数。
 *
 * <p>更正或差额使用 {@link SignedQuantity}；禁止用 {@code max(0)} 掩盖负数。
 */
public record Quantity(long value) {

    public static final Quantity ZERO = new Quantity(0);

    public Quantity {
        if (value < 0) {
            throw new IllegalArgumentException("正式数量不允许为负：" + value);
        }
    }

    public static Quantity of(long value) {
        return new Quantity(value);
    }

    public Quantity plus(Quantity other) {
        return new Quantity(Math.addExact(value, other.value));
    }

    public Quantity subtract(Quantity other) {
        return new Quantity(value - other.value);
    }
}
