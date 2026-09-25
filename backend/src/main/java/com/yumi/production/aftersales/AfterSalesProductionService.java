package com.yumi.production.aftersales;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.production.ProductionNodes;
import com.yumi.production.aftersales.internal.AfterSalesProductionSourceRepository;
import com.yumi.production.internal.AfterSalesProductionReference;
import com.yumi.production.plan.ProductionPlanService;
import com.yumi.production.plan.ProductionPlanViews;
import com.yumi.production.plan.internal.PlanWriter;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 售后生产来源应用服务（任务 8.4/8.5）：从售后退回返工或补发缺口创建售后生产计划。
 *
 * 口径（`docs/architecture/after-sales-module-design.md` §4.3，`domain-and-quantity-model.md` §12）：
 * <pre>
 * 售后返工来源额度 = 退回核验的返工数量 − 已安排
 * 补发生产来源额度 = 补发需求 − 已补发 − 可补发 − 已安排
 * </pre>
 * 售后计划（`AFTER_SALES_REWORK` / `AFTER_SALES_REPLACEMENT`）**不登记订单侧计划占用**
 * （售后数量不属于订单工序需求），核验合格只增加售后可补发，不进入订单履约也不自动进入通用库存。
 * 锁定顺序：售后明细行 → 来源行（见施工文档 §8）。
 */
@Service
public class AfterSalesProductionService {

    static final String PURPOSE_REWORK = "REWORK";
    static final String PURPOSE_REPLACEMENT = "REPLACEMENT";
    static final String TYPE_AFTER_SALES_REWORK = "AFTER_SALES_REWORK";
    static final String TYPE_AFTER_SALES_REPLACEMENT = "AFTER_SALES_REPLACEMENT";
    static final String SOURCE_TYPE = "AFTER_SALES_SOURCE";

    private final AfterSalesProductionSourceRepository repository;
    private final AfterSalesProductionReference reference;
    private final ProductionPlanService planService;
    private final PlanWriter planWriter;
    private final EmployeeEligibilityService eligibility;
    private final AuditContext auditContext;

    public AfterSalesProductionService(AfterSalesProductionSourceRepository repository,
                                       AfterSalesProductionReference reference,
                                       ProductionPlanService planService, PlanWriter planWriter,
                                       EmployeeEligibilityService eligibility, AuditContext auditContext) {
        this.repository = repository;
        this.reference = reference;
        this.planService = planService;
        this.planWriter = planWriter;
        this.eligibility = eligibility;
        this.auditContext = auditContext;
    }

    public List<AfterSalesProductionViews.AfterSalesSourceView> listSources(long caseId) {
        return repository.findByCase(caseId).stream()
                .map(row -> new AfterSalesProductionViews.AfterSalesSourceView(row.id(), row.afterSalesItemId(),
                        row.purpose(), row.node(), row.totalQuantity(), row.arrangedQuantity(), row.balance(),
                        row.reason(), row.version()))
                .toList();
    }

    @Transactional
    public ProductionPlanViews.PlanView createPlan(long caseId,
                                                   AfterSalesProductionViews.CreateAfterSalesPlanRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        String purpose = requirePurpose(request.purpose(), errors);
        String node = requireNode(request.node(), errors);
        int quantity = requirePositiveQuantity(request.quantity(), errors);
        if (request.planDate() == null) {
            errors.add(new ApiFieldError("planDate", "计划日期必填"));
        }
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "执行员工必填"));
        }
        if (request.afterSalesItemId() == null) {
            errors.add(new ApiFieldError("afterSalesItemId", "售后明细必填"));
        }
        failIfInvalid(errors);

        // 锁定顺序：售后明细行 → 来源行；都用锁定读，并发创建计划不会各自读到未安排的余额
        var context = reference.itemForUpdate(request.afterSalesItemId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "售后明细不存在"));
        if (context.caseId() != caseId) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "售后明细不属于该售后单",
                    List.of(new ApiFieldError("afterSalesItemId", "售后明细不属于该售后单")));
        }

        int total = PURPOSE_REWORK.equals(purpose) ? context.reworkBalance() : context.replacementBalance();
        if (total <= 0) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT,
                    PURPOSE_REWORK.equals(purpose) ? "该售后明细没有可返工数量" : "该售后明细没有待生产补发缺口",
                    List.of(new ApiFieldError("quantity", PURPOSE_REWORK.equals(purpose)
                            ? "退回核验的返工数量为 0" : "补发需求已被已补发与可补发覆盖")));
        }
        var existing = repository.findForUpdate(context.afterSalesItemId(), purpose, node).orElse(null);
        int arranged = existing == null ? 0 : existing.arrangedQuantity();
        if (quantity > total - arranged) {
            throw new ApiException(ErrorCode.SOURCE_INSUFFICIENT, "售后来源余额不足",
                    List.of(new ApiFieldError("quantity", "来源可安排 " + (total - arranged)
                            + "，本次计划 " + quantity)));
        }

        EmployeeSnapshot employee = eligibility.checkEligible(request.employeeId(), node);
        var audit = auditContext.current();
        long sourceId;
        if (existing == null) {
            sourceId = repository.insert(context.afterSalesItemId(), context.orderId(), context.orderItemId(),
                    purpose, node, total, request.note(), audit.requestId());
        } else {
            sourceId = existing.id();
            if (total > existing.totalQuantity()) {
                repository.raiseTotal(sourceId, total, audit.requestId());
            }
        }
        String planType = PURPOSE_REWORK.equals(purpose) ? TYPE_AFTER_SALES_REWORK : TYPE_AFTER_SALES_REPLACEMENT;
        long planId = planWriter.insertForAfterSales(planType, context.orderId(), context.orderItemId(), node,
                request.planDate(), request.employeeId(), employee.name(), quantity, SOURCE_TYPE, sourceId, 0,
                request.note(), audit.requestId());
        repository.arrange(sourceId, quantity, audit.requestId());
        return planService.get(planId);
    }

    private static String requirePurpose(String purpose, List<ApiFieldError> errors) {
        if (purpose == null || !(PURPOSE_REWORK.equals(purpose) || PURPOSE_REPLACEMENT.equals(purpose))) {
            errors.add(new ApiFieldError("purpose", "用途必须是 REWORK 或 REPLACEMENT"));
            return null;
        }
        return purpose;
    }

    private static String requireNode(String node, List<ApiFieldError> errors) {
        if (node == null || !ProductionNodes.isNode(node)) {
            errors.add(new ApiFieldError("node", "工序必须是 MAKING/PACKING_BAG/SEAM_CUTTING"));
            return null;
        }
        return node;
    }

    private static int requirePositiveQuantity(Integer quantity, List<ApiFieldError> errors) {
        if (quantity == null || quantity < 1) {
            errors.add(new ApiFieldError("quantity", "计划数量必须大于 0"));
            return 0;
        }
        return quantity;
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}
