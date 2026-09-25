package com.yumi.production.source;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.ProductionNodes;
import com.yumi.production.plan.ProductionPlanService;
import com.yumi.production.plan.ProductionPlanViews;
import com.yumi.production.plan.internal.PlanWriter;
import com.yumi.production.source.internal.RemakeSourceRepository;
import com.yumi.production.verification.internal.ProductionVerificationRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 报废重做来源应用服务（任务 5.7）：来源创建（受起始工序范围、原因与核验报废数量约束）与从来源创建计划。
 *
 * 口径（`domain-and-quantity-model.md` §7/§8，施工文档 §3.5/§4.5）：
 * 核验时已自动生成「默认从报废工序开始」的来源；本接口用于显式选择更早的起始工序（含从制作开始，
 * 此时**必须填写原因**）。同一核验下所有重做来源的总量合计不得超过该核验的报废数量；
 * 原报废事实永久保留，重做不恢复原报废数量、不增加订单需求。
 */
@Service
public class RemakeSourceService {

    private final RemakeSourceRepository repository;
    private final ProductionVerificationRepository verificationRepository;
    private final ProductionPlanService planService;
    private final PlanWriter planWriter;
    private final EmployeeEligibilityService eligibility;
    private final FulfillmentLedger ledger;
    private final AuditContext auditContext;

    public RemakeSourceService(RemakeSourceRepository repository,
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

    public List<ProductionSourceViews.RemakeSourceView> list(Long orderItemId) {
        return repository.findByItem(orderItemId).stream().map(RemakeSourceService::toView).toList();
    }

    @Transactional
    public ProductionSourceViews.RemakeSourceView create(ProductionSourceViews.CreateRemakeSourceRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        var verification = request.verificationId() == null ? null
                : verificationRepository.findById(request.verificationId()).orElse(null);
        if (verification == null) {
            errors.add(new ApiFieldError("verificationId", "核验记录不存在"));
        }
        if (request.quantity() == null || request.quantity() < 1) {
            errors.add(new ApiFieldError("quantity", "重做数量必须大于 0"));
        }
        if (request.startNode() == null || !ProductionNodes.isNode(request.startNode())) {
            errors.add(new ApiFieldError("startNode", "起始工序必须是 MAKING/PACKING_BAG/SEAM_CUTTING"));
        }
        failIfInvalid(errors);

        if (verification.scrapQuantity() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "该核验没有报废数量",
                    List.of(new ApiFieldError("verificationId", "核验报废数量为 0")));
        }
        // 起始工序只能取报废工序或其前序（默认报废工序）
        if (ProductionNodes.indexOf(request.startNode()) > ProductionNodes.indexOf(verification.node())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "重做起始工序不能晚于报废工序",
                    List.of(new ApiFieldError("startNode", "报废工序 " + verification.node()
                            + " 的起始工序只能是它或其前序")));
        }
        // 从制作开始重做必须填写原因（前序材料不可用等）
        if (ProductionNodes.MAKING.equals(request.startNode())
                && (request.reason() == null || request.reason().isBlank())) {
            throw new ApiException(ErrorCode.REMAKE_REASON_REQUIRED, "从制作开始重做必须填写原因",
                    List.of(new ApiFieldError("reason", "请填写前序材料不可用等原因")));
        }
        int already = repository.totalByVerification(verification.id());
        if (already + request.quantity() > verification.scrapQuantity()) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "重做来源总量超过该核验的报废数量",
                    List.of(new ApiFieldError("quantity", "该核验报废 " + verification.scrapQuantity()
                            + "，已建来源 " + already + "，本次 " + request.quantity())));
        }
        if (repository.findByIdForVerificationTarget(verification.id(), request.startNode()).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT_DUPLICATE, "该核验的该起始工序已有重做来源",
                    List.of(new ApiFieldError("startNode", "同一核验同一起始工序只能有一条来源")));
        }

        var audit = auditContext.current();
        long id = repository.insert(verification.id(), verification.orderId(), verification.orderItemId(),
                verification.node(), request.startNode(), request.quantity(), request.reason(), audit.requestId());
        return toView(repository.findById(id).orElseThrow());
    }

    @Transactional
    public ProductionPlanViews.PlanView createPlan(long sourceId,
                                                   ProductionSourceViews.CreateSourcePlanRequest request) {
        var source = repository.findByIdForUpdate(sourceId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "重做来源不存在"));
        var errors = new ArrayList<ApiFieldError>();
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
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "重做来源余额不足",
                    List.of(new ApiFieldError("quantity", "来源可安排 " + source.balance()
                            + "，本次计划 " + request.quantity())));
        }

        EmployeeSnapshot employee = eligibility.checkEligible(request.employeeId(), source.startNode());
        var audit = auditContext.current();
        long planId = planWriter.insert("REMAKE", source.orderId(), source.orderItemId(), source.startNode(),
                request.planDate(), request.employeeId(), employee.name(), request.quantity(),
                "REMAKE_SOURCE", source.id(), 0, request.note(), audit.requestId());
        repository.arrange(source.id(), request.quantity(), audit.requestId());
        ledger.applyRemakePending(source.orderItemId(), request.quantity(), false, audit.requestId());
        return planService.get(planId);
    }

    static ProductionSourceViews.RemakeSourceView toView(RemakeSourceRepository.RemakeSourceRow row) {
        return new ProductionSourceViews.RemakeSourceView(row.id(), row.verificationId(), row.orderId(),
                row.orderItemId(), row.scrapNode(), row.startNode(), row.totalQuantity(), row.arrangedQuantity(),
                row.balance(), row.reason(), row.version());
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}
