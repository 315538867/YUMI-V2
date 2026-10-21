package com.yumi.workforce.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 调整预留请求：调整必须在一个事务中释放不再使用的预留并建立新预留。
 *
 * <p>由 production 或 orders（减量/售后义务缩减）在同一事务中调用；只允许减少或更换为仍有资格的主体。
 */
public record ChangeWorkforceActivityCommand(
        WorkforceActivitySourceRef source,
        AssignmentSubjectRef subject,
        String workTypeCode,
        LocalDate workDate,
        List<String> memberEmployeeBusinessIds) {

    public ChangeWorkforceActivityCommand {
        if (source == null || subject == null) {
            throw new IllegalArgumentException("活动来源与执行主体不能为空");
        }
        memberEmployeeBusinessIds = List.copyOf(
                memberEmployeeBusinessIds == null ? List.of() : memberEmployeeBusinessIds);
    }
}
