package com.yumi.production.verification;

/**
 * 生产核验入参（任务 5.4）：合格 + 返工 + 报废必须等于本次完成；
 * 未完成数量由服务端按「计划数量 − 本次完成」计算，不接受客户端提交。
 */
public record VerifyProductionPlanRequest(
        Integer completedQuantity,
        Integer qualifiedQuantity,
        Integer reworkQuantity,
        Integer scrapQuantity,
        String verifyNote) {
}
