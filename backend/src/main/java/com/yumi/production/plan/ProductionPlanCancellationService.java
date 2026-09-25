package com.yumi.production.plan;

import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.plan.internal.PlanWriter;
import com.yumi.production.plan.internal.ProductionPlanRepository;
import com.yumi.production.source.internal.RemakeSourceRepository;
import com.yumi.production.source.internal.ReworkSourceRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 待执行计划取消（任务 5.8）：只允许取消 `PENDING` 计划并必须填写原因。
 *
 * 口径（`production-management`「待执行计划取消必须恢复来源」，施工文档 §3.1/§5）：
 * 正常计划取消只需释放计划占用（待安排数量本身是派生量）；返工/重做计划取消还要把数量退回来源
 * `arranged_quantity`（余额恢复，原计划与来源关系保留）；超额任务的预占释放在 5.11。
 * 已核验计划返回 `STATE_NOT_CANCELABLE`，核验与来源事实不变。
 * 锁定顺序：履约余额 → 计划 → 来源。
 */
@Service
public class ProductionPlanCancellationService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String TYPE_REWORK = "REWORK";
    private static final String TYPE_REMAKE = "REMAKE";
    private static final String TYPE_AFTER_SALES_REWORK = "AFTER_SALES_REWORK";
    private static final String TYPE_AFTER_SALES_REPLACEMENT = "AFTER_SALES_REPLACEMENT";

    private final ProductionPlanRepository planRepository;
    private final ProductionPlanService planService;
    private final PlanWriter planWriter;
    private final ReworkSourceRepository reworkSourceRepository;
    private final RemakeSourceRepository remakeSourceRepository;
    private final com.yumi.production.aftersales.internal.AfterSalesProductionSourceRepository
            afterSalesSourceRepository;
    private final FulfillmentLedger ledger;
    private final AuditContext auditContext;

    public ProductionPlanCancellationService(ProductionPlanRepository planRepository,
                                             ProductionPlanService planService, PlanWriter planWriter,
                                             ReworkSourceRepository reworkSourceRepository,
                                             RemakeSourceRepository remakeSourceRepository,
                                             com.yumi.production.aftersales.internal
                                                     .AfterSalesProductionSourceRepository
                                                     afterSalesSourceRepository,
                                             FulfillmentLedger ledger, AuditContext auditContext) {
        this.planRepository = planRepository;
        this.planService = planService;
        this.planWriter = planWriter;
        this.reworkSourceRepository = reworkSourceRepository;
        this.remakeSourceRepository = remakeSourceRepository;
        this.afterSalesSourceRepository = afterSalesSourceRepository;
        this.ledger = ledger;
        this.auditContext = auditContext;
    }

    @Transactional
    public ProductionPlanViews.PlanView cancel(long planId, String reason) {
        // 锁定顺序：计划行 → 履约余额（与核验一致，见施工文档 §8）；都用锁定读，避免快照读到旧状态
        var plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "生产计划不存在"));
        if (!isAfterSales(plan.planType())) {
            ledger.lockBalance(plan.orderItemId());
        }

        if (!STATUS_PENDING.equals(plan.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, "只有待执行计划可以取消",
                    List.of(new ApiFieldError("planId", "当前状态 " + plan.status())));
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "取消原因必填",
                    List.of(new ApiFieldError("reason", "请填写取消原因")));
        }

        var audit = auditContext.current();
        restoreSource(plan, audit.requestId());
        if (!isAfterSales(plan.planType())) {
            planWriter.releasePlanned(plan.orderItemId(), plan.node(), plan.quantity(), audit.requestId());
        }
        planRepository.markCancelled(planId, audit.adminUsername(), reason, audit.requestId());
        return planService.get(planId);
    }

    private static boolean isAfterSales(String planType) {
        return TYPE_AFTER_SALES_REWORK.equals(planType) || TYPE_AFTER_SALES_REPLACEMENT.equals(planType);
    }

    /**
     * 返工/重做计划取消后把数量退回来源余额并同步「待安排」投影；正常计划无需回退；
     * 售后计划只退回售后来源余额（售后数量不在订单侧占用，见 8.4/8.5）。
     */
    private void restoreSource(com.yumi.production.plan.internal.ProductionPlanRow plan, String requestId) {
        if (TYPE_REWORK.equals(plan.planType())) {
            reworkSourceRepository.release(plan.sourceId(), plan.quantity(), requestId);
            ledger.applyReworkPending(plan.orderItemId(), plan.quantity(), true, requestId);
        } else if (TYPE_REMAKE.equals(plan.planType())) {
            remakeSourceRepository.release(plan.sourceId(), plan.quantity(), requestId);
            ledger.applyRemakePending(plan.orderItemId(), plan.quantity(), true, requestId);
        } else if (isAfterSales(plan.planType())) {
            afterSalesSourceRepository.release(plan.sourceId(), plan.quantity(), requestId);
        }
    }
}
