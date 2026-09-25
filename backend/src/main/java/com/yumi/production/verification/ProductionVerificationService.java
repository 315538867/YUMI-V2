package com.yumi.production.verification;

import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.ProductionNodes;
import com.yumi.production.internal.OrderProductionReference;
import com.yumi.production.plan.internal.ExecutableCalculator;
import com.yumi.production.plan.internal.ProductionPlanRepository;
import com.yumi.production.plan.internal.ProductionPlanRow;
import com.yumi.production.reminder.internal.ProductionReminderRepository;
import com.yumi.production.source.internal.RemakeSourceRepository;
import com.yumi.production.source.internal.ReworkSourceRepository;
import com.yumi.production.verification.internal.ProductionVerificationRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产核验应用服务（任务 5.4/5.5）：一次性核验 + 逐工序合格流转 + 返工/报废来源 + 未完成处理。
 *
 * 口径（`domain-and-quantity-model.md` §7，施工文档 §4.3）：
 * <pre>
 * 本次完成 = 合格 + 返工 + 报废        未完成 = 计划数量 − 本次完成
 * </pre>
 * 合格按冻结流程流入下一节点：制作→捏毛装袋；捏毛装袋按冻结的缝边数量分流到缝边剪袋与可发货；缝边剪袋→可发货。
 * 返工生成待安排返工来源（默认回到同工序），报废生成待安排重做来源（默认从报废工序开始），
 * 两者都不自动创建计划、都不增加订单需求。
 *
 * 锁定顺序（施工文档 §8）：履约余额 → 计划 → 来源。核验前先锁履约投影行，再锁计划行，
 * 然后重算「当前可执行数量」——并发核验不会各自通过上限校验。
 */
@Service
public class ProductionVerificationService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_VERIFIED = "VERIFIED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String TYPE_NORMAL = "NORMAL";
    private static final String TYPE_REWORK = "REWORK";
    private static final String TYPE_REMAKE = "REMAKE";
    private static final String TYPE_OVERTIME = "OVERTIME";
    private static final String TYPE_AFTER_SALES_REWORK = "AFTER_SALES_REWORK";
    private static final String TYPE_AFTER_SALES_REPLACEMENT = "AFTER_SALES_REPLACEMENT";
    /** 超额待核验提醒被计划待调整提醒接管时的处理类型。 */
    private static final String HANDLING_SUPERSEDED = "SUPERSEDED";
    private static final String ENTRY_QUALIFIED = "PRODUCTION_QUALIFIED";
    /** 售后可补发入库事实类型（8.4/8.5）：售后返工/售后生产合格进入可补发。 */
    private static final String ENTRY_AFTER_SALES_PRODUCTION = "PRODUCTION_INFLOW";
    private static final String SOURCE_PRODUCTION = "PRODUCTION";

    private final ProductionPlanRepository planRepository;
    private final ProductionVerificationRepository verificationRepository;
    private final ReworkSourceRepository reworkSourceRepository;
    private final RemakeSourceRepository remakeSourceRepository;
    private final com.yumi.production.aftersales.internal.AfterSalesProductionSourceRepository
            afterSalesSourceRepository;
    private final ProductionReminderRepository reminderRepository;
    private final com.yumi.production.overtime.internal.OvertimePreemptionRepository preemptionRepository;
    private final OrderProductionReference reference;
    private final ExecutableCalculator calculator;
    private final FulfillmentLedger ledger;
    private final com.yumi.orders.ledger.AfterSalesLedger afterSalesLedger;
    private final AuditContext auditContext;

    public ProductionVerificationService(ProductionPlanRepository planRepository,
                                         ProductionVerificationRepository verificationRepository,
                                         ReworkSourceRepository reworkSourceRepository,
                                         RemakeSourceRepository remakeSourceRepository,
                                         com.yumi.production.aftersales.internal
                                                 .AfterSalesProductionSourceRepository afterSalesSourceRepository,
                                         ProductionReminderRepository reminderRepository,
                                         com.yumi.production.overtime.internal.OvertimePreemptionRepository
                                                 preemptionRepository,
                                         OrderProductionReference reference, ExecutableCalculator calculator,
                                         FulfillmentLedger ledger,
                                         com.yumi.orders.ledger.AfterSalesLedger afterSalesLedger,
                                         AuditContext auditContext) {
        this.planRepository = planRepository;
        this.verificationRepository = verificationRepository;
        this.reworkSourceRepository = reworkSourceRepository;
        this.remakeSourceRepository = remakeSourceRepository;
        this.afterSalesSourceRepository = afterSalesSourceRepository;
        this.reminderRepository = reminderRepository;
        this.preemptionRepository = preemptionRepository;
        this.reference = reference;
        this.calculator = calculator;
        this.ledger = ledger;
        this.afterSalesLedger = afterSalesLedger;
        this.auditContext = auditContext;
    }

    @Transactional
    public ProductionVerificationViews.VerificationView verify(long planId,
                                                              VerifyProductionPlanRequest request) {
        var audit = auditContext.current();
        // 先锁计划行再锁履约余额（锁定顺序见施工文档 §8）。两处都用锁定读：
        // 若先做普通 SELECT，事务快照会在加锁前建立，并发核验将看不到对方刚提交的核验而双双通过上限校验。
        var plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "生产计划不存在"));
        boolean afterSales = isAfterSalesType(plan.planType());
        if (!afterSales) {
            ledger.lockBalance(plan.orderItemId());
        }
        requirePending(plan);

        var errors = new ArrayList<ApiFieldError>();
        int completed = requireNonNegative(request.completedQuantity(), "completedQuantity", errors);
        int qualified = requireNonNegative(request.qualifiedQuantity(), "qualifiedQuantity", errors);
        int rework = requireNonNegative(request.reworkQuantity(), "reworkQuantity", errors);
        int scrap = requireNonNegative(request.scrapQuantity(), "scrapQuantity", errors);
        failIfInvalid(errors);
        requireEquation(completed, qualified, rework, scrap, plan.quantity());

        // 售后计划（8.4/8.5）：来源额度在创建时已校验，合格只进售后可补发，不参与订单工序流入与待安排
        var context = afterSales ? null : reference.item(plan.orderItemId());
        if (!afterSales) {
            if (context == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "订单明细不存在");
            }
            int verified = verifiedQuantity(plan.orderItemId(), plan.node());
            int executable = calculator.executableFor(plan.orderItemId(), plan.node(), planId,
                    context.effectiveInflow(plan.node()), verified);
            if (completed > executable) {
                throw new ApiException(ErrorCode.QUANTITY_NOT_EXECUTABLE, "本次完成超过当前可执行数量",
                        List.of(new ApiFieldError("completedQuantity",
                                "当前可执行 " + executable + "，本次完成 " + completed)));
            }
        }

        int incomplete = plan.quantity() - completed;
        long verificationId = verificationRepository.insert(planId, plan.orderId(), plan.orderItemId(),
                plan.node(), completed, qualified, rework, scrap, incomplete, request.verifyNote(),
                audit.adminUsername(), audit.requestId());

        var flows = new ArrayList<ProductionVerificationViews.FlowView>();
        if (afterSales) {
            if (qualified > 0) {
                long afterSalesItemId = requireAfterSalesItemId(plan);
                afterSalesLedger.registerInflow(afterSalesItemId, ENTRY_AFTER_SALES_PRODUCTION, qualified,
                        SOURCE_PRODUCTION, planId, 0, plan.planDate(), audit.adminUsername(),
                        (TYPE_AFTER_SALES_REWORK.equals(plan.planType()) ? "售后返工合格（" : "售后生产合格（")
                                + plan.planNo() + "）", audit.requestId());
                flows.add(new ProductionVerificationViews.FlowView("AFTER_SALES_AVAILABLE", qualified));
            }
        } else {
            for (var flow : qualifiedFlows(plan.node(), qualified, context)) {
                ledger.registerInflow(plan.orderId(), plan.orderItemId(), ENTRY_QUALIFIED, flow.node(),
                        flow.quantity(), SOURCE_PRODUCTION, planId, 0, plan.planDate(), audit.adminUsername(),
                        "生产核验合格（" + plan.planNo() + "）", audit.requestId());
                ledger.applyInflow(plan.orderItemId(), flow.node(), flow.quantity(), true, audit.requestId());
                flows.add(flow);
            }
            if (rework > 0) {
                // 返工额度进入「待安排」，来源由 5.6 按目标工序显式创建（同一核验可拆给多个目标工序）
                ledger.applyReworkPending(plan.orderItemId(), rework, true, audit.requestId());
            }
            if (scrap > 0) {
                ledger.applyRemakePending(plan.orderItemId(), scrap, true, audit.requestId());
            }
            // 计划不再占用；本次完成计入已核验处理（全部工序合计）
            ledger.applyPlanned(plan.orderItemId(), plan.node(), plan.quantity(), false, audit.requestId());
            ledger.applyVerified(plan.orderItemId(), completed, audit.requestId());
        }

        Long incompleteReminderId = handleIncomplete(plan, verificationId, incomplete, audit.requestId(),
                audit.adminUsername());
        if (TYPE_OVERTIME.equals(plan.planType())) {
            settleOvertime(plan, verificationId, qualified, audit.requestId(), audit.adminUsername());
        }
        planRepository.markVerified(planId, audit.requestId());

        return new ProductionVerificationViews.VerificationView(verificationId, planId, plan.planNo(),
                plan.orderItemId(), plan.node(), plan.quantity(), completed, qualified, rework, scrap,
                incomplete, request.verifyNote(), audit.adminUsername(), flows, incompleteReminderId);
    }

    private static boolean isAfterSalesType(String planType) {
        return TYPE_AFTER_SALES_REWORK.equals(planType) || TYPE_AFTER_SALES_REPLACEMENT.equals(planType);
    }

    /** 售后计划的来源行 → 售后明细：合格事实登记到售后可补发台账。 */
    private long requireAfterSalesItemId(ProductionPlanRow plan) {
        return afterSalesSourceRepository.findById(plan.sourceId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "售后生产来源不存在"))
                .afterSalesItemId();
    }

    private void requirePending(ProductionPlanRow plan) {
        if (STATUS_VERIFIED.equals(plan.status())) {
            throw new ApiException(ErrorCode.STATE_ALREADY_VERIFIED);
        }
        if (STATUS_CANCELLED.equals(plan.status())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "已取消的计划不可核验",
                    List.of(new ApiFieldError("planId", "计划已取消，请重新创建计划")));
        }
        if (!STATUS_PENDING.equals(plan.status())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "计划状态不可核验",
                    List.of(new ApiFieldError("planId", "当前状态 " + plan.status())));
        }
    }

    private static void requireEquation(int completed, int qualified, int rework, int scrap, int planQuantity) {
        if (completed != qualified + rework + scrap) {
            throw new ApiException(ErrorCode.VERIFICATION_EQUATION_INVALID,
                    "本次完成必须等于合格 + 返工 + 报废",
                    List.of(new ApiFieldError("completedQuantity", "完成 " + completed + " ≠ 合格 " + qualified
                            + " + 返工 " + rework + " + 报废 " + scrap)));
        }
        if (completed > planQuantity) {
            throw new ApiException(ErrorCode.VERIFICATION_EQUATION_INVALID, "本次完成不得超过计划数量",
                    List.of(new ApiFieldError("completedQuantity",
                            "计划 " + planQuantity + "，本次完成 " + completed)));
        }
    }

    /** 合格数量的分流：捏毛装袋按冻结的缝边数量分到缝边剪袋与可发货，其余工序直接进下一节点。 */
    private static List<ProductionVerificationViews.FlowView> qualifiedFlows(
            String node, int qualified, OrderProductionReference.OrderItemContext context) {
        if (qualified <= 0) {
            return List.of();
        }
        if (ProductionNodes.PACKING_BAG.equals(node)) {
            int remainingSeam = Math.max(0, context.seamQuantity() - context.seamInflow());
            int seam = Math.min(remainingSeam, qualified);
            var flows = new ArrayList<ProductionVerificationViews.FlowView>();
            if (seam > 0) {
                flows.add(new ProductionVerificationViews.FlowView(ProductionNodes.SEAM_CUTTING, seam));
            }
            if (qualified - seam > 0) {
                flows.add(new ProductionVerificationViews.FlowView(ProductionNodes.SHIPPABLE, qualified - seam));
            }
            return flows;
        }
        var target = ProductionNodes.qualifiedTarget(node);
        return target == null
                ? List.of()
                : List.of(new ProductionVerificationViews.FlowView(target, qualified));
    }

    /**
     * 未完成处理：正常计划生成「未完成待处理」提醒（待安排数量本身是派生量，无需回写）；
     * 返工/重做计划把未完成数量回退来源余额。超额任务与售后类型的未完成按各自口径处理
     * （超额释放预占在 5.11，售后在阶段八）。
     */
    private Long handleIncomplete(ProductionPlanRow plan, long verificationId, int incomplete, String requestId,
                                  String operatorUsername) {
        if (incomplete <= 0) {
            return null;
        }
        if (TYPE_REWORK.equals(plan.planType())) {
            reworkSourceRepository.release(plan.sourceId(), incomplete, requestId);
            ledger.applyReworkPending(plan.orderItemId(), incomplete, false, requestId);
            return null;
        }
        if (TYPE_REMAKE.equals(plan.planType())) {
            remakeSourceRepository.release(plan.sourceId(), incomplete, requestId);
            ledger.applyRemakePending(plan.orderItemId(), incomplete, false, requestId);
            return null;
        }
        if (TYPE_AFTER_SALES_REWORK.equals(plan.planType()) || TYPE_AFTER_SALES_REPLACEMENT.equals(
                plan.planType())) {
            // 未完成的售后数量退回来源余额，可重新排产；不产生订单侧待安排与提醒
            afterSalesSourceRepository.release(plan.sourceId(), incomplete, requestId);
            return null;
        }
        if (TYPE_NORMAL.equals(plan.planType())) {
            return reminderRepository.insertIncomplete(plan.orderId(), plan.orderItemId(), plan.node(), plan.id(),
                    verificationId, incomplete, requestId);
        }
        return null;
    }

    /**
     * 超额任务核验后的结算（任务 5.11，施工文档 §4.6）：
     * ① 释放该任务的全部有效预占（预占只用于借用未来产能，核验后结算）；
     * ② 结束创建时的「超额待核验」提醒——合格 &gt; 0 标记为已由计划待调整接管，零合格标记为无需调整；
     * ③ 合格 &gt; 0 时按预占顺序把**合格数量**分配到受影响的未来计划，生成「计划待调整」提醒
     * （建议减少数量 = 合格数量分摊，绝不超过该计划上的预占量）；返工/报废不产生计划减少建议。
     */
    private void settleOvertime(ProductionPlanRow plan, long verificationId, int qualified, String requestId,
                                String operatorUsername) {
        var active = preemptionRepository.findByOvertimePlan(plan.id()).stream()
                .filter(row -> "ACTIVE".equals(row.status()))
                .toList();
        preemptionRepository.releaseAll(plan.id(), "超额任务核验完成，预占结算", operatorUsername, requestId);

        for (var reminder : reminderRepository.findByOvertimePlan(plan.id())) {
            if (ProductionReminderRepository.TYPE_OVERTIME_PENDING_VERIFY.equals(reminder.reminderType())
                    && "OPEN".equals(reminder.status())) {
                reminderRepository.markHandled(reminder.id(),
                        qualified > 0 ? HANDLING_SUPERSEDED : "NO_ADJUSTMENT", null,
                        qualified > 0 ? "核验合格，已转入计划待调整" : "零合格，无需调整",
                        operatorUsername, requestId);
            }
        }
        if (qualified <= 0) {
            return;
        }
        int remaining = qualified;
        for (var preemption : active) {
            if (remaining <= 0) {
                break;
            }
            int suggestion = Math.min(preemption.preemptedQuantity(), remaining);
            reminderRepository.insertPlanAdjustment(plan.orderId(), plan.orderItemId(), plan.node(), plan.id(),
                    verificationId, preemption.id(), preemption.futurePlanId(), suggestion, requestId);
            remaining -= suggestion;
        }
    }

    private int verifiedQuantity(long orderItemId, String node) {        return planRepository.verifiedByItemNode(List.of(orderItemId))
                .getOrDefault(orderItemId + ":" + node, 0);
    }

    private static int requireNonNegative(Integer value, String field, List<ApiFieldError> errors) {
        if (value == null || value < 0) {
            errors.add(new ApiFieldError(field, "数量必填且不得为负"));
            return 0;
        }
        return value;
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}
