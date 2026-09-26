package com.yumi.production.overtime;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超额任务读模型与入参（阶段五 5.14，施工文档 §3.7/§8/§9）。
 *
 * 口径：超额任务只在执行当天创建，来源只能是未来日期的 `NORMAL` 任务明细；
 * 订单、产品与工序一律由服务端从被引用的未来明细解析，**不接受客户端传入**；
 * 预占不修改未来明细原计划数量、不产生工序流入或完成事实。
 */
public final class OvertimeTaskViews {

    private OvertimeTaskViews() {
    }

    /** 超额任务头 + 明细 + 预占 + 提醒（提醒只是工作台提示，不是数量事实）。 */
    public record OvertimeTaskView(
            long id,
            String taskNo,
            LocalDate taskDate,
            long employeeId,
            String employeeName,
            String workType,
            String workTypeName,
            String note,
            long version,
            List<OvertimeItemView> items,
            List<OvertimePreemptionView> preemptions,
            List<OvertimeReminderView> reminders) {
    }

    /** 超额明细：自身承载一次性核验事实（PENDING → VERIFIED），不进入普通核验表。 */
    public record OvertimeItemView(
            long id,
            int itemNo,
            long orderId,
            long orderItemId,
            long productId,
            String productNo,
            String productName,
            String node,
            int plannedQuantity,
            String status,
            Integer completedQuantity,
            Integer qualifiedQuantity,
            Integer reworkQuantity,
            Integer scrapQuantity,
            Integer incompleteQuantity,
            String verifyNote,
            String verifiedBy,
            LocalDateTime verifiedAt,
            long version) {
    }

    /** 预占：不修改未来明细原计划数量，核验后整体释放。 */
    public record OvertimePreemptionView(
            long id,
            long overtimeItemId,
            long futureTaskItemId,
            long orderId,
            long orderItemId,
            String node,
            int preemptedQuantity,
            String status,
            LocalDateTime releasedAt,
            String releasedBy,
            String releaseReason) {
    }

    public record OvertimeReminderView(
            long id,
            String reminderType,
            long orderId,
            long orderItemId,
            String node,
            Long preemptionId,
            Long futureTaskItemId,
            int quantity,
            String status,
            String handlingType,
            String reason) {
    }

    /**
     * 创建超额任务入参：执行日期必须是服务器当前业务日期；每条明细只给出未来明细引用与预占数量，
     * 订单、产品、工序由服务端从未来明细解析。
     */
    public record CreateOvertimeTaskRequest(
            LocalDate taskDate,
            Long employeeId,
            String note,
            List<CreateOvertimeItemRequest> items) {

        public record CreateOvertimeItemRequest(Long futureTaskItemId, Integer plannedQuantity) {
        }
    }

    /** 核验入参：一次提交本任务明细；等式 completed = qualified + rework + scrap，且 completed ≤ planned。 */
    public record VerifyOvertimeTaskRequest(List<VerifyOvertimeItemRequest> items) {

        public record VerifyOvertimeItemRequest(
                Long overtimeItemId,
                Integer completedQuantity,
                Integer qualifiedQuantity,
                Integer reworkQuantity,
                Integer scrapQuantity,
                String note) {
        }
    }
}
