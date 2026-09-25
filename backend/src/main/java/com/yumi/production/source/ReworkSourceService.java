package com.yumi.production.source;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.ProductionNodes;
import com.yumi.production.plan.ProductionPlanService;
import com.yumi.production.plan.ProductionPlanViews;
import com.yumi.production.plan.internal.PlanWriter;
import com.yumi.production.source.internal.ReworkSourceRepository;
import com.yumi.production.verification.internal.ProductionVerificationRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 返工来源应用服务（任务 5.6）：来源创建（受目标矩阵与核验返工数量约束）与从来源创建计划（受来源余额约束）。
 *
 * 口径（`domain-and-quantity-model.md` §6.1/§7，施工文档 §3.4/§4.4）：
 * 核验时已自动生成「默认回到同工序」的来源；本接口用于显式为更早的前序工序另建来源。
 * 同一核验下所有返工来源的总量合计不得超过该核验的返工数量；同一 (核验, 目标工序) 只有一条来源。
 * 从来源创建计划时先锁来源行再校验余额，并发创建不会超支。
 */
@Service
public class ReworkSourceService {

    private final ReworkSourceRepository repository;
    private final ProductionVerificationRepository verificationRepository;
    private final ProductionPlanService planService;
    private final PlanWriter planWriter;
    private final EmployeeEligibilityService eligibility;
    private final FulfillmentLedger ledger;
    private final AuditContext auditContext;

    public ReworkSourceService(ReworkSourceRepository repository,
                               ProductionVerificationRepository verificationRepository,
                               ProductionPlanService planService, PlanWriter planWriter,
                               EmployeeEligibilityService eligibility, FulfillmentLedger ledger,
                               AuditContext auditContext) {
        this.repository = repository;
        this.verificationRepository = verificationRepository;
        this.planService = planService;
        this.planWriter = planWriter;
        this.eligibility = eligibility;
        this.ledger = ledger;
        this.auditContext = auditContext;
    }

    public List<ProductionSourceViews.ReworkSourceView> list(Long orderItemId) {
        return repository.findByItem(orderItemId).stream().map(ReworkSourceService::toView).toList();
    }

    @Transactional
    public ProductionSourceViews.ReworkSourceView create(ProductionSourceViews.CreateReworkSourceRequest request) {
        var errors = new java.util.ArrayList<ApiFieldError>();
        var verification = request.verificationId() == null ? null
                : verificationRepository.findById(request.verificationId()).orElse(null);
        if (verification == null) {
            errors.add(new ApiFieldError("verificationId", "核验记录不存在"));
        }
        if (request.quantity() == null || request.quantity() < 1) {
            errors.add(new ApiFieldError("quantity", "返工数量必须大于 0"));
        }
        failIfInvalid(errors);

        if (verification.reworkQuantity() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "该核验没有返工数量",
                    List.of(new ApiFieldError("verificationId", "核验返工数量为 0")));
        }
        if (!ProductionNodes.canRework(verification.node(), request.targetNode())) {
            throw new ApiException(ErrorCode.REWORK_TARGET_INVALID, "返工目标工序不合法",
                    List.of(new ApiFieldError("targetNode", "发现问题工序 " + verification.node()
                            + " 只能返工 " + String.join("/", ProductionNodes.reworkTargets(verification.node())))));
        }
        int already = repository.totalByVerification(verification.id());
        if (already + request.quantity() > verification.reworkQuantity()) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "返工来源总量超过该核验的返工数量",
                    List.of(new ApiFieldError("quantity", "该核验返工 " + verification.reworkQuantity()
                            + "，已建来源 " + already + "，本次 " + request.quantity())));
        }
        if (repository.findByIdForVerificationTarget(verification.id(), request.targetNode()).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT_DUPLICATE, "该核验的该目标工序已有返工来源",
                    List.of(new ApiFieldError("targetNode", "同一核验同一目标工序只能有一条来源")));
        }

        var audit = auditContext.current();
        long id = repository.insert(verification.id(), verification.orderId(), verification.orderItemId(),
                verification.node(), request.targetNode(), request.quantity(), 1, null, request.reason(),
                audit.requestId());
        return toView(repository.findById(id).orElseThrow());
    }

    @Transactional
    public ProductionPlanViews.PlanView createPlan(long sourceId,
                                                   ProductionSourceViews.CreateSourcePlanRequest request) {
        var source = repository.findByIdForUpdate(sourceId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "返工来源不存在"));
        var errors = new java.util.ArrayList<ApiFieldError>();
        if (request.quantity() == null || request.quantity() < 1) {
            errors.add(new ApiFieldError("quantity", "计划数量必须大于 0"));
        }
        if (request.planDate() == null) {
            errors.add(new ApiFieldError("planDate", "计划日期必填"));
        }
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "执行员工必填"));
        }
        failIfInvalid(errors);
        if (request.quantity() > source.balance()) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "返工来源余额不足",
                    List.of(new ApiFieldError("quantity", "来源可安排 " + source.balance()
                            + "，本次计划 " + request.quantity())));
        }

        EmployeeSnapshot employee = eligibility.checkEligible(request.employeeId(), source.targetNode());
        var audit = auditContext.current();
        long planId = planWriter.insert("REWORK", source.orderId(), source.orderItemId(), source.targetNode(),
                request.planDate(), request.employeeId(), employee.name(), request.quantity(),
                "REWORK_SOURCE", source.id(), 0, request.note(), audit.requestId());
        repository.arrange(source.id(), request.quantity(), audit.requestId());
        ledger.applyReworkPending(source.orderItemId(), request.quantity(), false, audit.requestId());
        return planService.get(planId);
    }

    static ProductionSourceViews.ReworkSourceView toView(ReworkSourceRepository.ReworkSourceRow row) {
        return new ProductionSourceViews.ReworkSourceView(row.id(), row.verificationId(), row.orderId(),
                row.orderItemId(), row.foundNode(), row.targetNode(), row.totalQuantity(), row.arrangedQuantity(),
                row.balance(), row.roundNo(), row.previousSourceId(), row.reason(), row.version());
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}
