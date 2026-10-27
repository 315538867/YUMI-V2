package com.yumi.workforce.application.port;

import com.yumi.workforce.api.ReservationScopeType;
import com.yumi.workforce.api.ReservationStatus;
import com.yumi.workforce.api.WorkforceActivitySourceRef;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 活动守卫与预留持久化端口。 */
public interface ActivityReservationRepository {

    /**
     * 幂等建立守卫行并以 {@code SELECT ... FOR UPDATE} 加锁。
     *
     * <p>首次使用时先插入计数为 0 的行；已存在则锁定该行。不得锁不存在的投影充当保护。
     */
    GuardRecord lockGuard(ReservationScopeType guardType, String guardedBusinessId);

    /** 活动预留计数；守卫行不存在时为 0。 */
    int activeReferenceCount(ReservationScopeType guardType, String guardedBusinessId);

    void incrementGuard(ReservationScopeType guardType, String guardedBusinessId);

    void decrementGuard(ReservationScopeType guardType, String guardedBusinessId);

    void insertReservation(ReservationRecord record);

    List<ReservationRecord> findActive(WorkforceActivitySourceRef source);

    Optional<ReservationRecord> findActiveScope(
            WorkforceActivitySourceRef source, ReservationScopeType scopeType, String reservedBusinessId);

    /**
     * 释放预留：只改状态与时间，并减少守卫计数。
     *
     * @return 是否由本次活动释放（重复释放返回 false）
     */
    boolean release(String reservationBusinessId, java.time.Instant releasedAt);

    List<ReservationRecord> findAll(WorkforceActivitySourceRef source);

    List<ReservationRecord> findActiveByReserved(
            ReservationScopeType scopeType, String reservedBusinessId);

    /** 守卫行。 */
    record GuardRecord(
            ReservationScopeType guardType, String guardedBusinessId, int activeReferenceCount, long rowVersion) {
    }

    /** 预留记录。 */
    record ReservationRecord(
            String reservationBusinessId,
            String sourceModule,
            String sourceType,
            String sourceBusinessId,
            ReservationScopeType scopeType,
            String reservedBusinessId,
            String subjectSnapshotRef,
            String standardSnapshotRef,
            String workTypeCode,
            LocalDate workDate,
            ReservationStatus status) {
    }
}
