package com.yumi.production.reminder;

import java.time.LocalDate;

/** 生产提醒读模型（阶段五）：未完成待处理与超额相关提醒。 */
public final class ProductionReminderViews {

    private ProductionReminderViews() {
    }

    /** 未完成待处理：数量为剩余未完成数量，部分安排后余量继续提醒。 */
    public record IncompleteReminderView(
            long id,
            long orderId,
            String orderNo,
            long orderItemId,
            int lineNo,
            String productNo,
            String productName,
            String node,
            long planId,
            String planNo,
            int quantity,
            String status,
            String handlingType,
            Integer handledQuantity,
            String reason) {
    }

    /** 超额提醒：待核验或计划待调整，附着在受影响的未来计划行上。 */
    public record OvertimeReminderView(
            long id,
            String reminderType,
            long orderId,
            long orderItemId,
            String node,
            long overtimePlanId,
            String overtimePlanNo,
            Long futurePlanId,
            String futurePlanNo,
            LocalDate futurePlanDate,
            int quantity,
            String status,
            String handlingType,
            String reason) {
    }

    /** 重新安排未完成数量入参：数量可少于提醒余量（部分安排）。 */
    public record RescheduleRequest(
            LocalDate planDate,
            Long employeeId,
            Integer quantity,
            String note) {
    }

    /** 暂不安排入参：原因必填，不删除待安排需求。 */
    public record DeferRequest(String reason) {
    }

    /** 调整未来计划入参：新数量与修改原因必填。 */
    public record AdjustPlanRequest(Integer newQuantity, String reason) {
    }

    /** 无需调整入参：原因必填。 */
    public record NoAdjustmentRequest(String reason) {
    }
}
