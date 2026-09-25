package com.yumi.production.otherschedule;

import java.time.LocalDate;

/** 其他排班读模型与入参（阶段五）：以小时和 0–59 分钟录入，总分钟必须大于 0。 */
public final class OtherScheduleViews {

    private OtherScheduleViews() {
    }

    public record OtherScheduleView(
            long id,
            String scheduleNo,
            LocalDate scheduleDate,
            long employeeId,
            String employeeName,
            int hours,
            int minutes,
            int totalMinutes,
            String status,
            String note,
            Integer verifiedMinutes,
            Integer effectiveMinutes,
            boolean corrected,
            String cancelReason,
            long version) {
    }

    public record CreateOtherScheduleRequest(
            LocalDate scheduleDate,
            Long employeeId,
            Integer hours,
            Integer minutes,
            String note) {
    }

    /** 一次性工时核验入参：录入实际小时与分钟。 */
    public record VerifyOtherScheduleRequest(
            Integer hours,
            Integer minutes,
            String note) {
    }

    public record CancelOtherScheduleRequest(String reason) {
    }

    /** 工时更正入参：不改原核验，只追加更正事实。 */
    public record CorrectOtherScheduleRequest(
            Integer hours,
            Integer minutes,
            String reason) {
    }
}
