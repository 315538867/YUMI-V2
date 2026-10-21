package com.yumi.workforce.api;

import java.util.List;

/**
 * 已持锁校验的预留准备结果。
 *
 * <p>携带持锁取得的主体、成员、工种与标准身份/版本及快照载荷；生产工作量校验只使用该一致依据。
 * {@code prepare} 不写快照唯一键；{@code commit} 在生产来源锁之后固化。
 */
public record PreparedActivityReservation(
        ReserveWorkforceActivityCommand command,
        AssignmentSubjectSnapshotBundle bundle,
        List<ReservationScopeRef> scopeRefs,
        List<ActivityGuardRef> guards) {

    public PreparedActivityReservation {
        scopeRefs = List.copyOf(scopeRefs);
        guards = List.copyOf(guards);
    }

    /** 需要预留的范围引用。 */
    public record ReservationScopeRef(ReservationScopeType scopeType, String businessId) {
    }

    /** 已锁定的活动守卫。 */
    public record ActivityGuardRef(ReservationScopeType guardType, String guardedBusinessId) {
    }

    /** 以真实任务来源替换临时来源（prepare 阶段尚未分配任务标识时使用占位来源）。 */
    public PreparedActivityReservation withSource(WorkforceActivitySourceRef newSource) {
        return new PreparedActivityReservation(
                new ReserveWorkforceActivityCommand(
                        newSource,
                        command.subject(),
                        command.workTypeCode(),
                        command.workDate(),
                        command.memberEmployeeBusinessIds()),
                bundle,
                scopeRefs,
                guards);
    }

    /** 活动来源（由生产或订单提供）。 */
    public WorkforceActivitySourceRef source() {
        return command.source();
    }

    /** 本次准备使用的执行主体依据（持全部守卫后取得）。 */
    public AssignmentSubjectSnapshotBundle bundle() {
        return bundle;
    }
}
