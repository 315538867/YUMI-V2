package com.yumi.production.reminder.internal;

import java.time.LocalDateTime;

/** production_reminders 一行：工作台提示，不是数量事实来源，可由明细/核验/来源/预占重建。 */
public record ProductionReminderRow(
        Long id,
        String reminderType,
        long orderId,
        long orderItemId,
        String node,
        Long taskItemId,
        Long verificationId,
        Long preemptionId,
        Long futureTaskItemId,
        int quantity,
        String status,
        String handlingType,
        Integer handledQuantity,
        String reason,
        String handledBy,
        LocalDateTime handledAt) {
}
