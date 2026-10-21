package com.yumi.workforce.api;

/**
 * 活动引用参与接口（生产与订单在同一事务内调用）。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §5.4、§9、§10：
 * <ul>
 *   <li>只暴露预留语义，不暴露生产任务表或通用人员修改能力；</li>
 *   <li>人员变更与任务预留竞争同一守卫行，不能先查后改；</li>
 *   <li>预留与生产任务同事务成功或回滚；</li>
 *   <li>终态释放只改状态与计数，不删除历史。</li>
 * </ul>
 */
public interface WorkforceActivityReservationParticipant {

    PreparedActivityReservation prepareReserve(ReserveWorkforceActivityCommand command);

    ActivityReservationRefs commitReserve(PreparedActivityReservation prepared);

    PreparedActivityReservationChange prepareChange(ChangeWorkforceActivityCommand command);

    ActivityReservationRefs commitChange(PreparedActivityReservationChange prepared);

    PreparedActivityRelease prepareRelease(ReleaseWorkforceActivityCommand command);

    void commitRelease(PreparedActivityRelease prepared);
}
