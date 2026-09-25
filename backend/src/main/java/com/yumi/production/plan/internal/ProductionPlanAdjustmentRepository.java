package com.yumi.production.plan.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** `production_plan_adjustments` 访问（阶段五）：待执行正常计划的调整历史，只由超额提醒的「调整计划」写入。 */
@Repository
public class ProductionPlanAdjustmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductionPlanAdjustmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertQuantityAdjustment(long planId, int beforeQuantity, int afterQuantity, String reason,
                                        String requestId) {
        jdbcTemplate.update("""
                INSERT INTO production_plan_adjustments (plan_id, adjustment_type, before_quantity,
                    after_quantity, reason, created_at, updated_at, request_id)
                VALUES (?, 'QUANTITY', ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                """, planId, beforeQuantity, afterQuantity, reason, requestId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM production_plan_adjustments WHERE plan_id = ? ORDER BY id DESC LIMIT 1
                """, Long.class, planId);
    }
}
