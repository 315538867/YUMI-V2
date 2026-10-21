package com.yumi.workforce.api;

/** 已持锁校验的调整准备结果。 */
public record PreparedActivityReservationChange(
        PreparedActivityReservation prepared, ActivityReservationRefs existing) {
}
