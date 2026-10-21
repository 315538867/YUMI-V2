package com.yumi.production.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 安排任务命令。
 *
 * <p>生产/返工任务绑定来源并预留人员负载与产能；其他任务不关联生产来源，直接录入整体标准分钟。
 *
 * @param taskType 任务类型
 * @param subject 执行主体
 * @param workDate 工作日期
 * @param processCode 执行工序；其他任务必须为空
 * @param items 生产/返工任务明细；其他任务必须为空
 * @param enteredStandardMinutes 其他任务整体标准分钟；生产/返工必须为空
 * @param otherTask 其他任务详情；非其他任务必须为空
 * @param overrideReason 超出标准工作量的显式覆盖原因
 */
public record ArrangeTaskCommand(
        TaskType taskType,
        SubjectRef subject,
        LocalDate workDate,
        TargetProcess processCode,
        List<TaskItemRequest> items,
        Integer enteredStandardMinutes,
        OtherTaskRequest otherTask,
        String overrideReason) {

    public ArrangeTaskCommand {
        items = List.copyOf(items == null ? List.of() : items);
        if (taskType == null || subject == null || workDate == null) {
            throw new IllegalArgumentException("任务类型、执行主体与工作日期不能为空");
        }
        if (taskType == TaskType.OTHER_TASK) {
            if (enteredStandardMinutes == null || enteredStandardMinutes <= 0) {
                throw new IllegalArgumentException("其他任务必须直接录入整体标准分钟");
            }
            if (!items.isEmpty() || processCode != null) {
                throw new IllegalArgumentException("其他任务不关联生产来源与工序");
            }
            if (otherTask == null) {
                throw new IllegalArgumentException("其他任务必须填写工作内容");
            }
        } else {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("生产/返工任务必须至少有一条明细");
            }
            if (processCode == null) {
                throw new IllegalArgumentException("生产/返工任务必须有唯一执行工序");
            }
            if (enteredStandardMinutes != null) {
                throw new IllegalArgumentException("生产/返工任务的标准分钟来自明细，不能直接录入");
            }
            if (otherTask != null) {
                throw new IllegalArgumentException("生产/返工任务不使用其他任务详情");
            }
            if (taskType == TaskType.EXTRA_PRODUCTION && processCode != TargetProcess.MAKING) {
                throw new IllegalArgumentException("超额任务工序固定为 MAKING");
            }
            if (taskType == TaskType.REPAIR && items.size() != 1) {
                throw new IllegalArgumentException("返工任务只允许关联一个返工来源");
            }
        }
    }

    /** 其他任务详情。 */
    public record OtherTaskRequest(
            String workContent,
            String relatedProductBusinessId,
            Integer plannedQuantity) {

        public OtherTaskRequest {
            if (workContent == null || workContent.isBlank()) {
                throw new IllegalArgumentException("其他任务必须填写工作内容");
            }
            boolean hasProduct = relatedProductBusinessId != null && !relatedProductBusinessId.isBlank();
            if (hasProduct && (plannedQuantity == null || plannedQuantity <= 0)) {
                throw new IllegalArgumentException("关联商品的其他任务必须填写计划数量");
            }
            if (!hasProduct && plannedQuantity != null) {
                throw new IllegalArgumentException("未关联商品的其他任务不能填写计划数量");
            }
        }
    }
}
