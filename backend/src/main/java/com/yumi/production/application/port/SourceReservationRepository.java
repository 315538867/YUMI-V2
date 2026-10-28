package com.yumi.production.application.port;

import com.yumi.production.api.SubjectType;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 来源活动预留：活动任务占用的来源数量。
 *
 * <p>来源当前可安排数量 = 当前工序来源可用总量 − 活动任务预留数量（基线 11 §5.2.1）。
 * 预留只占用可安排余额，不改变需求义务、物理制品所有权或已发生事实。
 */
public interface SourceReservationRepository {

    /** 幂等建行并锁定来源预留行。 */
    int lockReservedQuantity(String productionSourceBusinessId);

    void reserve(String productionSourceBusinessId, int quantity);

    void release(String productionSourceBusinessId, int quantity);

    /**
     * 活动任务来源占用明细：任务、明细、来源与数量，用于核验与取消时精确释放。
     *
     * <p>{@code status} 为 {@code ACTIVE / RELEASED}；历史记录不删除。
     */
    record SourceHold(
            String taskBusinessId,
            String taskItemBusinessId,
            String productionSourceBusinessId,
            String repairSourceBusinessId,
            int quantity,
            String status) {
    }

    Optional<SourceHold> findHold(String taskItemBusinessId);

    void upsertActiveHold(SourceHold hold);

    void releaseHold(String taskItemBusinessId);

    /** 断言主体当日活动占用：供取消与改期时释放。 */
    void releaseSubjectReferences(String taskBusinessId, SubjectType subjectType, String subjectBusinessId,
            LocalDate workDate);
}
