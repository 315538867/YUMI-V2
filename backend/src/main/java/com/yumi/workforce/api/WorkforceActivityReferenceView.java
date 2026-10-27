package com.yumi.workforce.api;

import java.time.LocalDate;

/** 执行主体当前活动引用；来源业务标识用于定位生产任务。 */
public record WorkforceActivityReferenceView(
        String reservationBusinessId,
        String sourceModule,
        String sourceType,
        String sourceBusinessId,
        String workTypeCode,
        LocalDate workDate,
        ReservationStatus status) {
}
