package com.yumi.workforce.api;

import java.util.List;

/** 已持锁校验的释放准备结果。 */
public record PreparedActivityRelease(
        WorkforceActivitySourceRef source,
        List<ActivityReservationRefs.Reservation> activeReservations) {

    public PreparedActivityRelease {
        activeReservations = List.copyOf(activeReservations);
    }
}
