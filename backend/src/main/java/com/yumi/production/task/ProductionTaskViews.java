package com.yumi.production.task;

import java.time.LocalDate;
import java.util.List;

/**
 * 生产任务响应（阶段五）：任务头 + 明细 + 明细派生数量。
 * 派生数量（实际流入/已核验处理/当前可执行/等待上游/容量余额）全部由服务端在同一事务或同一读边界重算，
 * 客户端提交的同名字段一律忽略。
 */
public final class ProductionTaskViews {

    private ProductionTaskViews() {
    }

    /** 明细核验事实（阶段五 5.18）：逐明细合格/返工/报废/未完成与核验人时间，未核验时为 null。 */
    public record VerificationFactView(
            int plannedQuantity,
            int completedQuantity,
            int qualifiedQuantity,
            int reworkQuantity,
            int scrapQuantity,
            int incompleteQuantity,
            String note,
            String verifiedBy,
            String verifiedAt) {
    }

    /** 事实时间线事件：按 `factTime ASC, factType ASC, factId ASC` 稳定排序。 */
    public record FactView(
            String factTime,
            String factType,
            long factId,
            String node,
            Integer quantity,
            Long orderItemId,
            Long referenceId,
            String operator,
            String reason,
            String note) {
    }

    /** 明细视图：计划数量、实际流入、当前可执行与容量余额分栏返回，便于页面区分「计划/事实」。 */
    public record ItemView(
            long id,
            int itemNo,
            long orderId,
            String orderNo,
            long orderItemId,
            int lineNo,
            long productId,
            String productNo,
            String productName,
            String node,
            int plannedQuantity,
            String sourceType,
            long sourceId,
            Integer standardMinutes,
            Long estimatedMinutes,
            Long normalMinutes,
            Long reworkMinutes,
            String capacityNotice,
            Integer dailyMaxCapacity,
            Integer usedNormalQuantity,
            Integer remainingNormalQuantity,
            int actualInflow,
            int verifiedProcessedQuantity,
            int executableQuantity,
            boolean waitingUpstream,
            String status,
            String cancelledBy,
            String cancelReason,
            VerificationFactView verification,
            long version) {
    }

    /** 任务头视图：状态由明细事实派生，不提供手工状态覆盖。 */
    public record TaskView(
            long id,
            String taskNo,
            LocalDate taskDate,
            long employeeId,
            String employeeName,
            long workTypeId,
            String workTypeName,
            String taskType,
            String note,
            String derivedStatus,
            List<ItemView> items,
            long version) {
    }
}
