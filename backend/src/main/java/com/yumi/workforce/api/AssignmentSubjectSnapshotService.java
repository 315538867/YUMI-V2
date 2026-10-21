package com.yumi.workforce.api;

import java.time.LocalDate;

/**
 * 执行主体与时间标准快照。
 *
 * <p>规则来源：`docs/一期/10-人员与小组模块.md` §7.2：
 * 快照按“稳定标准标识 + 内容版本”区分；两个标准即使同为版本 1 也不得碰撞；
 * 历史成员在离职后仍可解析。
 */
public interface AssignmentSubjectSnapshotService {

    AssignmentSubjectSnapshotBundle acquireCurrentAssignmentSnapshot(
            AssignmentSubjectRef subject, String workTypeCode, LocalDate workDate);

    AssignmentSubjectSnapshotView resolve(AssignmentSubjectSnapshotRef ref);

    /** 读取全局每日有效工作时间标准快照。 */
    AssignmentSubjectSnapshotBundle.StandardSnapshotRef acquireCurrentDailyStandard();

    /** 按人数读取效率标准快照：先解析稳定身份，再按身份与版本复用。 */
    AssignmentSubjectSnapshotBundle.StandardSnapshotRef acquireCurrentTeamEfficiency(int memberCount);
}
