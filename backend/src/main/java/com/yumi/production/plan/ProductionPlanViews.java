package com.yumi.production.plan;

import java.time.LocalDate;

/** 生产计划读模型（阶段五）：数量为整数，待安排/可执行由事实派生。 */
public final class ProductionPlanViews {

    private ProductionPlanViews() {
    }

    /**
     * 计划视图。{@code schedulableQuantity} 为该工序剩余待安排数量，
     * {@code executableQuantity} 为本计划当前可执行数量（0 且待执行即「等待上游」）。
     */
    public record PlanView(
            long id,
            String planNo,
            String planType,
            long orderId,
            String orderNo,
            long orderItemId,
            int lineNo,
            String productNo,
            String productName,
            String node,
            LocalDate planDate,
            long employeeId,
            String employeeName,
            int quantity,
            String status,
            String sourceType,
            long sourceId,
            int nodeDemand,
            int nodePending,
            int nodeVerified,
            int nodeInflow,
            int schedulableQuantity,
            int executableQuantity,
            boolean waitingUpstream,
            String note,
            long version) {
    }
}
