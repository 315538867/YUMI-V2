package com.yumi.production.reminder;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.internal.OrderProductionReference;
import com.yumi.production.plan.internal.PlanWriter;
import com.yumi.production.plan.internal.ProductionPlanAdjustmentRepository;
import com.yumi.production.plan.internal.ProductionPlanRepository;
import com.yumi.production.plan.internal.ProductionPlanRow;
import com.yumi.production.reminder.internal.ProductionReminderRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产提醒应用服务（任务 5.9/5.12）。
 *
 * 未完成待处理（`production-management`「未完成数量必须返回对应待处理来源」）：
 * 重新安排（可部分，余量继续提醒）、暂不安排（原因必填，**不删除**待安排需求）。
 * 超额提醒（「超额提醒必须支持人工处理」）：调整未来计划（写 `production_plan_adjustments` 留痕并改计划数量）、
 * 无需调整（原因必填，不改计划）。
 *
 * 提醒只辅助工作台，不是数量事实来源；所有数量以计划、核验、来源与预占为准。
 */
@Service
public class ProductionReminderService {

    private static final String TYPE_INCOMPLETE = ProductionReminderRepository.TYPE_INCOMPLETE;
    private static final String TYPE_PLAN_ADJUSTMENT = ProductionReminderRepository.TYPE_PLAN_ADJUSTMENT;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_PENDING = "PENDING";
    private static final String HANDLING_RESCHEDULED = "RESCHEDULED";
    private static final String HANDLING_DEFERRED = "DEFERRED";
    private static final String HANDLING_ADJUSTED = "ADJUSTED";
    private static final String HANDLING_NO_ADJUSTMENT = "NO_ADJUSTMENT";

    private final ProductionReminderRepository repository;
    private final ProductionPlanRepository planRepository;
    private final ProductionPlanAdjustmentRepository adjustmentRepository;
    private final OrderProductionReference reference;
    private final PlanWriter planWriter;
    private final EmployeeEligibilityService eligibility;
    private final FulfillmentLedger ledger;
    private final AuditContext auditContext;

    public ProductionReminderService(ProductionReminderRepository repository,
                                     ProductionPlanRepository planRepository,
                                     ProductionPlanAdjustmentRepository adjustmentRepository,
                                     OrderProductionReference reference, PlanWriter planWriter,
                                     EmployeeEligibilityService eligibility, FulfillmentLedger ledger,
                                     AuditContext auditContext) {
        this.repository = repository;
        this.planRepository = planRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.reference = reference;
        this.planWriter = planWriter;
        this.eligibility = eligibility;
        this.ledger = ledger;
        this.auditContext = auditContext;
    }

    // ---------- 5.9 未完成待处理 ----------

    public List<ProductionReminderViews.IncompleteReminderView> listIncomplete() {
        return repository.findOpen(TYPE_INCOMPLETE).stream().map(this::toIncompleteView).toList();
    }

    /** 重新安排：数量不得超过提醒余量；用满则关闭提醒，部分则余量继续提醒。 */
    @Transactional
    public ProductionReminderViews.IncompleteReminderView reschedule(long reminderId,
                                                                     ProductionReminderViews.RescheduleRequest request) {
        var reminder = repository.findByIdForUpdate(reminderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "提醒不存在"));
        requireOpenIncomplete(reminder);
        var errors = new ArrayList<ApiFieldError>();
        if (request.quantity() == null || request.quantity() < 1) {
            errors.add(new ApiFieldError("quantity", "数量必须大于 0"));
        }
        if (request.planDate() == null) {
            errors.add(new ApiFieldError("planDate", "计划日期必填"));
        }
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "执行员工必填"));
        }
        failIfInvalid(errors);
        if (request.quantity() > reminder.quantity()) {
            throw new ApiException(ErrorCode.QUANTITY_INVALID, "重新安排数量超过未完成余量",
                    List.of(new ApiFieldError("quantity", "未完成余量 " + reminder.quantity()
                            + "，本次安排 " + request.quantity())));
        }

        EmployeeSnapshot employee = eligibility.checkEligible(request.employeeId(), reminder.node());
        var audit = auditContext.current();
        planWriter.insert("NORMAL", reminder.orderId(), reminder.orderItemId(), reminder.node(),
                request.planDate(), request.employeeId(), employee.name(), request.quantity(),
                "ORDER", reminder.orderId(), reminder.orderItemId(),
                request.note() == null ? "未完成重新安排" : request.note(), audit.requestId());

        if (request.quantity() == reminder.quantity()) {
            repository.markHandled(reminderId, HANDLING_RESCHEDULED, request.quantity(), null,
                    audit.adminUsername(), audit.requestId());
        } else {
            repository.reduceQuantity(reminderId, request.quantity(), audit.requestId());
        }
        return toIncompleteView(repository.findById(reminderId).orElseThrow());
    }

    /** 暂不安排：原因必填；待安排需求是派生量，不做任何删除。 */
    @Transactional
    public ProductionReminderViews.IncompleteReminderView defer(long reminderId,
                                                                ProductionReminderViews.DeferRequest request) {
        var reminder = repository.findByIdForUpdate(reminderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "提醒不存在"));
        requireOpenIncomplete(reminder);
        requireReason(request.reason());
        var audit = auditContext.current();
        repository.markHandled(reminderId, HANDLING_DEFERRED, null, request.reason(),
                audit.adminUsername(), audit.requestId());
        return toIncompleteView(repository.findById(reminderId).orElseThrow());
    }

    // ---------- 5.12 超额提醒 ----------

    public List<ProductionReminderViews.OvertimeReminderView> listOvertime() {
        return repository.findOpenOvertime().stream().map(this::toOvertimeView).toList();
    }

    /** 调整未来计划：写入调整历史（前后数量 + 原因）并同步计划数量与计划占用投影。 */
    @Transactional
    public ProductionReminderViews.OvertimeReminderView adjustPlan(long reminderId,
                                                                   ProductionReminderViews.AdjustPlanRequest request) {
        var reminder = repository.findByIdForUpdate(reminderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "提醒不存在"));
        requireOpen(reminder);
        if (!TYPE_PLAN_ADJUSTMENT.equals(reminder.reminderType())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "该提醒不是计划待调整",
                    List.of(new ApiFieldError("reminderId", "只有计划待调整可以改计划")));
        }
        requireReason(request.reason());
        // 计划数量必须大于 0（ck_production_plans_quantity）：要归零请改用「取消计划」，
        // 保留一条数量为 0 的待执行计划没有业务含义，也会破坏「有效计划占用」口径。
        if (request.newQuantity() == null || request.newQuantity() < 1) {
            throw new ApiException(ErrorCode.QUANTITY_INVALID, "调整后的计划数量必须大于 0",
                    List.of(new ApiFieldError("newQuantity", "若该计划无需生产，请取消计划而不是改成 0")));
        }
        var futurePlan = planRepository.findByIdForUpdate(reminder.futurePlanId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "未来计划不存在"));
        if (!STATUS_PENDING.equals(futurePlan.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "只有待执行计划可以调整",
                    List.of(new ApiFieldError("futurePlanId", "当前状态 " + futurePlan.status())));
        }

        var audit = auditContext.current();
        adjustmentRepository.insertQuantityAdjustment(futurePlan.id(), futurePlan.quantity(),
                request.newQuantity(), request.reason(), audit.requestId());
        planRepository.updateQuantity(futurePlan.id(), request.newQuantity(), audit.requestId());
        int delta = request.newQuantity() - futurePlan.quantity();
        if (delta != 0) {
            ledger.applyPlanned(futurePlan.orderItemId(), futurePlan.node(), Math.abs(delta), delta > 0,
                    audit.requestId());
        }
        repository.markHandled(reminderId, HANDLING_ADJUSTED, null, request.reason(),
                audit.adminUsername(), audit.requestId());
        return toOvertimeView(repository.findById(reminderId).orElseThrow());
    }

    /** 无需调整：原因必填，不修改计划。 */
    @Transactional
    public ProductionReminderViews.OvertimeReminderView noAdjustment(
            long reminderId, ProductionReminderViews.NoAdjustmentRequest request) {
        var reminder = repository.findByIdForUpdate(reminderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "提醒不存在"));
        requireOpen(reminder);
        requireReason(request.reason());
        var audit = auditContext.current();
        repository.markHandled(reminderId, HANDLING_NO_ADJUSTMENT, null, request.reason(),
                audit.adminUsername(), audit.requestId());
        return toOvertimeView(repository.findById(reminderId).orElseThrow());
    }

    // ---------- 内部 ----------

    private static void requireOpen(ProductionReminderRepository.ReminderRow reminder) {
        if (!STATUS_OPEN.equals(reminder.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CANCELABLE, "提醒已处理",
                    List.of(new ApiFieldError("reminderId", "当前状态 " + reminder.status())));
        }
    }

    private static void requireOpenIncomplete(ProductionReminderRepository.ReminderRow reminder) {
        requireOpen(reminder);
        if (!TYPE_INCOMPLETE.equals(reminder.reminderType())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "该提醒不是未完成待处理",
                    List.of(new ApiFieldError("reminderId", "只有未完成待处理可以重新安排")));
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "原因必填",
                    List.of(new ApiFieldError("reason", "请填写原因")));
        }
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }

    private ProductionReminderViews.IncompleteReminderView toIncompleteView(
            ProductionReminderRepository.ReminderRow row) {
        var context = reference.item(row.orderItemId());
        var plan = row.planId() == null ? null : planRepository.findById(row.planId()).orElse(null);
        return new ProductionReminderViews.IncompleteReminderView(row.id(), row.orderId(),
                context == null ? null : context.orderNo(), row.orderItemId(),
                context == null ? 0 : context.lineNo(),
                context == null ? null : context.productNo(), context == null ? null : context.productName(),
                row.node(), row.planId() == null ? 0 : row.planId(), plan == null ? null : plan.planNo(),
                row.quantity(), row.status(), row.handlingType(), row.handledQuantity(), row.reason());
    }

    private ProductionReminderViews.OvertimeReminderView toOvertimeView(
            ProductionReminderRepository.ReminderRow row) {
        var overtimePlan = row.planId() == null ? null : planRepository.findById(row.planId()).orElse(null);
        ProductionPlanRow futurePlan = row.futurePlanId() == null ? null
                : planRepository.findById(row.futurePlanId()).orElse(null);
        return new ProductionReminderViews.OvertimeReminderView(row.id(), row.reminderType(), row.orderId(),
                row.orderItemId(), row.node(), row.planId() == null ? 0 : row.planId(),
                overtimePlan == null ? null : overtimePlan.planNo(), row.futurePlanId(),
                futurePlan == null ? null : futurePlan.planNo(),
                futurePlan == null ? null : futurePlan.planDate(), row.quantity(), row.status(),
                row.handlingType(), row.reason());
    }
}
