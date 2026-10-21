package com.yumi.platform.api.value;

/**
 * 整数秒：任务工作量、占用负载、剩余时间。
 *
 * <p>公式内部、持久化和比较统一使用整数秒；需要分钟时在展示边界转换。
 */
public record DurationSeconds(long value) {

    public static final DurationSeconds ZERO = new DurationSeconds(0);

    public DurationSeconds {
        if (value < 0) {
            throw new IllegalArgumentException("秒数不允许为负：" + value);
        }
    }

    public static DurationSeconds of(long value) {
        return new DurationSeconds(value);
    }

    public DurationSeconds plus(DurationSeconds other) {
        return new DurationSeconds(Math.addExact(value, other.value));
    }

    public DurationSeconds minusClampedAtZero(DurationSeconds other) {
        return new DurationSeconds(Math.max(0L, value - other.value));
    }

    public boolean exceeds(DurationSeconds other) {
        return value > other.value;
    }

    /** 展示用：向上取整为分钟，不参与业务计算。 */
    public DurationMinutes toMinutesRoundedUp() {
        long minutes = Math.ceilDiv(value, 60L);
        if (minutes > Integer.MAX_VALUE) {
            throw new ArithmeticException("分钟数超出 int 范围：" + minutes);
        }
        return DurationMinutes.of((int) minutes);
    }
}
