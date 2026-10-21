package com.yumi.platform.api.value;

/** 有符号数量差额，仅用于更正事实。 */
public record SignedQuantity(long value) {

    public static final SignedQuantity ZERO = new SignedQuantity(0);

    public static SignedQuantity of(long value) {
        return new SignedQuantity(value);
    }

    public SignedQuantity negate() {
        return new SignedQuantity(-value);
    }
}
