package com.yumi.production.source;

import java.time.LocalDate;

/** 返工/重做来源读模型（阶段五）：来源余额 = 总量 − 已安排。 */
public final class ProductionSourceViews {

    private ProductionSourceViews() {
    }

    public record ReworkSourceView(
            long id,
            long verificationId,
            long orderId,
            long orderItemId,
            String foundNode,
            String targetNode,
            int totalQuantity,
            int arrangedQuantity,
            int balanceQuantity,
            int roundNo,
            Long previousSourceId,
            String reason,
            long version) {
    }

    public record RemakeSourceView(
            long id,
            long verificationId,
            long orderId,
            long orderItemId,
            String scrapNode,
            String startNode,
            int totalQuantity,
            int arrangedQuantity,
            int balanceQuantity,
            String reason,
            long version) {
    }

    /** 从来源创建计划入参（返工/重做共用）。 */
    public record CreateSourcePlanRequest(
            LocalDate planDate,
            Long employeeId,
            Integer quantity,
            String note) {
    }

    /** 创建返工来源入参：目标工序必须落在「发现问题工序或其前序」矩阵内。 */
    public record CreateReworkSourceRequest(
            Long verificationId,
            String targetNode,
            Integer quantity,
            String reason) {
    }

    /** 创建重做来源入参：起始工序默认报废工序；选择从制作开始时必须填写原因。 */
    public record CreateRemakeSourceRequest(
            Long verificationId,
            String startNode,
            Integer quantity,
            String reason) {
    }
}
