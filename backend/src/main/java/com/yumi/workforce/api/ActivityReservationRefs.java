package com.yumi.workforce.api;

import java.util.List;

/**
 * 提交预留后的结果：任务保存这些预留引用，后续调整与释放按引用操作。
 */
public record ActivityReservationRefs(
        WorkforceActivitySourceRef source,
        List<Reservation> reservations,
        AssignmentSubjectSnapshotBundle bundle) {

    public ActivityReservationRefs {
        reservations = List.copyOf(reservations);
    }

    /** 单条预留。 */
    public record Reservation(
            String reservationBusinessId,
            ReservationScopeType scopeType,
            String reservedBusinessId,
            ReservationStatus status) {
    }
}
