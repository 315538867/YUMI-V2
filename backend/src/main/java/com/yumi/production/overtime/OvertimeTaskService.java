package com.yumi.production.overtime;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.production.internal.OrderProductionReference;
import com.yumi.production.overtime.internal.OvertimePreemptionRepository;
import com.yumi.production.plan.internal.PlanWriter;
import com.yumi.production.plan.internal.ProductionPlanRepository;
import com.yumi.production.reminder.internal.ProductionReminderRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 超额任务应用服务（任务 5.10）。
 *
 * 口径（`domain-and-quantity-model.md` §11，施工文档 §4.6）：
 * 只在**执行当天**创建；来源只能是**未来日期**的正常计划；预占校验
 * `Σ 某未来计划的有效预占 ≤ 该计划计划数量 − 其他有效预占合计`（未来计划 id 升序加锁，并发不超支）；
 * **预占不修改未来计划原始数量、不产生工序流入或完成**；创建时为每条预占生成「超额待核验」提醒。
 * 核验与后续提醒处理见 `ProductionVerificationService.settleOvertime` 与 5.12。
 */
@Service
public class OvertimeTaskService {

    private static final String TYPE_OVERTIME = "OVERTIME";
    private static final String TYPE_NORMAL = "NORMAL";
    private static final String STATUS_PENDING = "PENDING";
    private static final String ORDER_CONFIRMED = "CONFIRMED";

    private final OvertimePreemptionRepository preemptionRepository;
    private final ProductionPlanRepository planRepository;
    private final ProductionReminderRepository reminderRepository;
    private final OrderProductionReference reference;
    private final PlanWriter planWriter;
    private final EmployeeEligibilityService eligibility;
    private final AuditContext auditContext;

    public OvertimeTaskService(OvertimePreemptionRepository preemptionRepository,
                               ProductionPlanRepository planRepository,
                               ProductionReminderRepository reminderRepository,
                               OrderProductionReference reference, PlanWriter planWriter,
                               EmployeeEligibilityService eligibility, AuditContext auditContext) {
        this.preemptionRepository = preemptionRepository;
        this.planRepository = planRepository;
        this.reminderRepository = reminderRepository;
        this.reference = reference;
        this.planWriter = planWriter;
        this.eligibility = eligibility;
        this.auditContext = auditContext;
    }

    @Transactional
    public OvertimeTaskViews.OvertimeTaskView create(OvertimeTaskViews.CreateOvertimeTaskRequest request) {
        var today = LocalDate.now();
        if (request.planDate() == null || !today.equals(request.planDate())) {
            throw new ApiException(ErrorCode.OVERTIME_DATE_INVALID, "超额任务只能在执行当天创建",
                    List.of(new ApiFieldError("planDate", "执行日期必须是今天 " + today)));
        }
        var errors = new ArrayList<ApiFieldError>();
        var context = request.orderItemId() == null ? null : reference.item(request.orderItemId());
        if (request.orderItemId() == null) {
            errors.add(new ApiFieldError("orderItemId", "订单明细必填"));
        } else if (context == null) {
            errors.add(new ApiFieldError("orderItemId", "订单明细不存在"));
        } else if (!ORDER_CONFIRMED.equals(context.status())) {
            errors.add(new ApiFieldError("orderItemId", "仅已确认订单可以创建超额任务"));
        }
        if (request.node() == null || !com.yumi.production.ProductionNodes.isNode(request.node())) {
            errors.add(new ApiFieldError("node", "工序必须是 MAKING/PACKING_BAG/SEAM_CUTTING"));
        }
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "执行员工必填"));
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            errors.add(new ApiFieldError("lines", "至少一条来源计划"));
        }
        failIfInvalid(errors);

        var lines = request.lines();
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.futurePlanId() == null) {
                errors.add(new ApiFieldError("lines[" + index + "].futurePlanId", "来源计划必填"));
            }
            if (line.quantity() == null || line.quantity() < 1) {
                errors.add(new ApiFieldError("lines[" + index + "].quantity", "预占数量必须大于 0"));
            }
        }
        failIfInvalid(errors);

        // 未来计划 id 升序加锁：并发超额任务不会各自通过可选数量校验
        var futurePlanIds = lines.stream().map(OvertimeTaskViews.CreateOvertimeTaskRequest.Line::futurePlanId)
                .distinct().sorted().toList();
        preemptionRepository.lockFuturePlans(futurePlanIds);

        var plannedByFuturePlan = new java.util.LinkedHashMap<Long, Integer>();
        var futurePlans = new java.util.LinkedHashMap<Long, com.yumi.production.plan.internal.ProductionPlanRow>();
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var prefix = "lines[" + index + "]";
            var futurePlan = futurePlans.computeIfAbsent(line.futurePlanId(),
                    id -> planRepository.findById(id).orElse(null));
            if (futurePlan == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "来源计划不存在",
                        List.of(new ApiFieldError(prefix + ".futurePlanId", "来源计划不存在")));
            }
            if (!TYPE_NORMAL.equals(futurePlan.planType()) || !STATUS_PENDING.equals(futurePlan.status())) {
                throw new ApiException(ErrorCode.OVERTIME_DATE_INVALID, "来源必须是待执行的正常计划",
                        List.of(new ApiFieldError(prefix + ".futurePlanId",
                                "计划类型 " + futurePlan.planType() + "、状态 " + futurePlan.status())));
            }
            if (!futurePlan.planDate().isAfter(today)) {
                throw new ApiException(ErrorCode.OVERTIME_DATE_INVALID, "来源计划必须是未来日期",
                        List.of(new ApiFieldError(prefix + ".futurePlanId",
                                "计划日期 " + futurePlan.planDate() + " 不晚于今天")));
            }
            plannedByFuturePlan.merge(futurePlan.id(), line.quantity(), Integer::sum);
        }

        for (var entry : plannedByFuturePlan.entrySet()) {
            var futurePlan = futurePlans.get(entry.getKey());
            int available = futurePlan.quantity() - preemptionRepository.activeQuantity(entry.getKey());
            if (entry.getValue() > available) {
                throw new ApiException(ErrorCode.OVERTIME_RESERVATION_EXCEEDED, "预占超过未来计划未预占余额",
                        List.of(new ApiFieldError("lines", "计划 " + futurePlan.planNo() + " 可选 " + available
                                + "，本次预占 " + entry.getValue())));
            }
        }

        EmployeeSnapshot employee = eligibility.checkEligible(request.employeeId(), request.node());
        var audit = auditContext.current();
        int totalQuantity = lines.stream().mapToInt(OvertimeTaskViews.CreateOvertimeTaskRequest.Line::quantity).sum();
        long planId = planWriter.insert(TYPE_OVERTIME, context.orderId(), context.orderItemId(), request.node(),
                request.planDate(), request.employeeId(), employee.name(), totalQuantity,
                "NONE", 0, 0, request.note(), audit.requestId());

        for (var entry : plannedByFuturePlan.entrySet()) {
            var futurePlan = futurePlans.get(entry.getKey());
            long preemptionId = preemptionRepository.insert(planId, futurePlan.id(), futurePlan.orderId(),
                    futurePlan.orderItemId(), request.node(), entry.getValue(), audit.requestId());
            reminderRepository.insertOvertimePendingVerify(context.orderId(), context.orderItemId(),
                    request.node(), planId, preemptionId, futurePlan.id(), entry.getValue(), audit.requestId());
        }
        return view(planId);
    }

    public OvertimeTaskViews.OvertimeTaskView view(long planId) {
        var plan = planRepository.findById(planId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "超额任务不存在"));
        var preemptions = preemptionRepository.findByOvertimePlan(planId).stream()
                .map(row -> new OvertimeTaskViews.PreemptionView(row.id(), row.futurePlanId(),
                        planRepository.findById(row.futurePlanId()).map(p -> p.planNo()).orElse(null),
                        planRepository.findById(row.futurePlanId()).map(p -> p.planDate()).orElse(null),
                        row.preemptedQuantity(), row.status()))
                .toList();
        return new OvertimeTaskViews.OvertimeTaskView(plan.id(), plan.planNo(), plan.node(), plan.planDate(),
                plan.orderItemId(), plan.employeeId(), plan.employeeName(), plan.quantity(), plan.status(),
                preemptions);
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}
