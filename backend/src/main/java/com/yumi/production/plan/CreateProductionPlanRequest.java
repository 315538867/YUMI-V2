package com.yumi.production.plan;

import java.time.LocalDate;

/**
 * 新建生产计划入参（任务 5.2）。
 * 本接口只创建**正常计划**（`planType = NORMAL`）：返工/重做/超额计划必须从各自来源创建
 * （`POST /api/rework-sources/{id}/plans`、`POST /api/remake-sources/{id}/plans`、`POST /api/overtime-tasks`），
 * 以保证来源余额与目标矩阵在创建时即被校验。
 * 售后两类计划在阶段八接入售后来源后启用。
 */
public record CreateProductionPlanRequest(
        String planType,
        Long orderItemId,
        String node,
        LocalDate planDate,
        Long employeeId,
        Integer quantity,
        String note) {
}
