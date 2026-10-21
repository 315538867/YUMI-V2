package com.yumi.workforce.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 预留请求（生产模块在同一事务内调用）。
 *
 * @param source 活动来源
 * @param subject 执行主体
 * @param workTypeCode 目标工种；为空表示该活动没有工种资格要求（其他任务）
 * @param workDate 工作日期
 * @param memberEmployeeBusinessIds 小组任务时冻结的成员集合；员工任务时为空
 */
public record ReserveWorkforceActivityCommand(
        WorkforceActivitySourceRef source,
        AssignmentSubjectRef subject,
        String workTypeCode,
        LocalDate workDate,
        List<String> memberEmployeeBusinessIds) {

    public ReserveWorkforceActivityCommand {
        if (source == null || subject == null) {
            throw new IllegalArgumentException("活动来源与执行主体不能为空");
        }
        if (workDate == null) {
            throw new IllegalArgumentException("工作日期不能为空");
        }
        workTypeCode = workTypeCode == null || workTypeCode.isBlank() ? null : workTypeCode.strip();
        memberEmployeeBusinessIds = List.copyOf(
                memberEmployeeBusinessIds == null ? List.of() : memberEmployeeBusinessIds);
    }
}
