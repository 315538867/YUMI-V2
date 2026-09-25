package com.yumi.production.plan;

import com.yumi.catalog.employee.dto.EmployeeSnapshot;
import com.yumi.catalog.employee.service.EmployeeEligibilityService;
import com.yumi.identity.AuditContext;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.ProductionNodes;
import com.yumi.production.internal.OrderProductionReference;
import com.yumi.production.plan.internal.ExecutableCalculator;
import com.yumi.production.plan.internal.PlanWriter;
import com.yumi.production.plan.internal.ProductionPlanRepository;
import com.yumi.production.plan.internal.ProductionPlanRow;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产计划应用服务（任务 5.2/5.3）：正常计划的创建与查询，以及待安排/当前可执行/等待上游的派生。
 *
 * 口径（`domain-and-quantity-model.md` §6.1，施工文档 §4.2）：
 * <pre>
 * 待安排数量   = 工序总需求 − 该工序有效待执行计划占用 − 该工序已核验处理
 * 当前可执行量 = 工序有效流入 − 该工序已核验处理，再按「计划日期 + id」升序依次分给待执行计划
 * </pre>
 * 「已核验处理」按工序从核验事实汇总，不读订单侧投影——保证可从事实重建。
 * 创建计划**不产生**完成、库存或履约事实，只登记计划占用（订单侧排产状态用）。
 */
@Service
public class ProductionPlanService {

    static final String TYPE_NORMAL = "NORMAL";
    private static final String TYPE_AFTER_SALES_REWORK = "AFTER_SALES_REWORK";
    private static final String TYPE_AFTER_SALES_REPLACEMENT = "AFTER_SALES_REPLACEMENT";
    private static final String STATUS_PENDING = "PENDING";
    private static final String ORDER_CONFIRMED = "CONFIRMED";

    private final ProductionPlanRepository repository;
    private final ExecutableCalculator calculator;
    private final PlanWriter planWriter;
    private final OrderProductionReference reference;
    private final EmployeeEligibilityService eligibility;
    private final FulfillmentLedger ledger;
    private final AuditContext auditContext;

    public ProductionPlanService(ProductionPlanRepository repository, ExecutableCalculator calculator,
                                 PlanWriter planWriter, OrderProductionReference reference,
                                 EmployeeEligibilityService eligibility, FulfillmentLedger ledger,
                                 AuditContext auditContext) {
        this.repository = repository;
        this.calculator = calculator;
        this.planWriter = planWriter;
        this.reference = reference;
        this.eligibility = eligibility;
        this.ledger = ledger;
        this.auditContext = auditContext;
    }

    @Transactional
    public ProductionPlanViews.PlanView create(CreateProductionPlanRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        requireNormalType(request.planType(), errors);
        var node = requireNode(request.node(), errors);
        var quantity = requirePositiveQuantity(request.quantity(), errors);
        if (request.planDate() == null) {
            errors.add(new ApiFieldError("planDate", "计划日期必填"));
        }
        var context = requireItem(request.orderItemId(), errors);
        if (request.employeeId() == null) {
            errors.add(new ApiFieldError("employeeId", "执行员工必填"));
        }
        failIfInvalid(errors);
        // 先锁履约余额再算待安排：MySQL REPEATABLE READ 下若先做普通 SELECT，事务快照会提前建立，
        // 并发创建计划将看不到对方刚提交的计划而双双通过「待安排数量」校验。
        ledger.lockBalance(context.orderItemId());

        // 员工资格由 catalog 的员工应用服务判定：离职或缺该工种 → EMPLOYEE_NOT_ELIGIBLE
        EmployeeSnapshot employee = eligibility.checkEligible(request.employeeId(), node);
        int schedulable = schedulableQuantity(context, node);
        if (quantity > schedulable) {
            throw new ApiException(ErrorCode.QUANTITY_INVALID,
                    "计划数量超过该工序待安排数量",
                    List.of(new ApiFieldError("quantity",
                            "工序 " + node + " 待安排 " + schedulable + "，本次计划 " + quantity)));
        }

        var audit = auditContext.current();
        long id = planWriter.insert(TYPE_NORMAL, context.orderId(), context.orderItemId(), node,
                request.planDate(), request.employeeId(), employee.name(), quantity,
                "ORDER", context.orderId(), context.orderItemId(), request.note(), audit.requestId());
        return get(id);
    }

    public List<ProductionPlanViews.PlanView> list(LocalDate dateFrom, LocalDate dateTo, Long employeeId,
                                                   String node, String status, Long orderId, Long orderItemId) {
        return toViews(repository.find(dateFrom, dateTo, employeeId, node, status, orderId, orderItemId));
    }

    public ProductionPlanViews.PlanView get(long id) {
        var row = repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "生产计划不存在"));
        return toViews(List.of(row)).get(0);
    }

    /** 该明细该工序的待安排数量（计划创建与调整的上限）。 */
    int schedulableQuantity(OrderProductionReference.OrderItemContext context, String node) {
        int pending = repository.pendingByItemNode(List.of(context.orderItemId()))
                .getOrDefault(key(context.orderItemId(), node), 0);
        int verified = repository.verifiedByItemNode(List.of(context.orderItemId()))
                .getOrDefault(key(context.orderItemId(), node), 0);
        return Math.max(0, context.demand(node) - pending - verified);
    }

    private List<ProductionPlanViews.PlanView> toViews(List<ProductionPlanRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var itemIds = rows.stream().map(ProductionPlanRow::orderItemId).distinct().toList();
        var contexts = reference.items(itemIds);
        var pending = repository.pendingByItemNode(itemIds);
        var verified = repository.verifiedByItemNode(itemIds);
        var executableCache = new HashMap<String, Map<Long, Integer>>();

        return rows.stream().map(row -> {
            var context = contexts.get(row.orderItemId());
            if (isAfterSalesType(row.planType())) {
                // 售后计划（8.4/8.5）：数量来自售后来源额度，不参与订单工序需求与当前可执行分配
                return new ProductionPlanViews.PlanView(row.id(), row.planNo(), row.planType(), row.orderId(),
                        context == null ? null : context.orderNo(), row.orderItemId(),
                        context == null ? 0 : context.lineNo(),
                        context == null ? null : context.productNo(),
                        context == null ? null : context.productName(),
                        row.node(), row.planDate(), row.employeeId(), row.employeeName(), row.quantity(),
                        row.status(), row.sourceType(), row.sourceId(),
                        row.quantity(), 0, 0, row.quantity(),
                        0,
                        STATUS_PENDING.equals(row.status()) ? row.quantity() : 0,
                        false, row.note(), row.version());
            }
            int demand = context == null ? 0 : context.demand(row.node());
            int nodePending = pending.getOrDefault(key(row.orderItemId(), row.node()), 0);
            int nodeVerified = verified.getOrDefault(key(row.orderItemId(), row.node()), 0);
            int inflow = context == null ? 0 : context.effectiveInflow(row.node());
            var executable = executableCache.computeIfAbsent(key(row.orderItemId(), row.node()),
                    cacheKey -> calculator.allocate(row.orderItemId(), row.node(), inflow, nodeVerified));
            int own = executable.getOrDefault(row.id(), 0);
            return new ProductionPlanViews.PlanView(row.id(), row.planNo(), row.planType(), row.orderId(),
                    context == null ? null : context.orderNo(), row.orderItemId(),
                    context == null ? 0 : context.lineNo(),
                    context == null ? null : context.productNo(), context == null ? null : context.productName(),
                    row.node(), row.planDate(), row.employeeId(), row.employeeName(), row.quantity(),
                    row.status(), row.sourceType(), row.sourceId(),
                    demand, nodePending, nodeVerified, inflow,
                    Math.max(0, demand - nodePending - nodeVerified), own,
                    STATUS_PENDING.equals(row.status()) && own == 0,
                    row.note(), row.version());
        }).toList();
    }

    /**
     * 当前可执行量按「计划日期 + id」升序依次分配（先到先得），由 {@link ExecutableCalculator} 统一实现，
     * 与核验时的上限校验共用同一口径。
     */
    private static String key(long orderItemId, String node) {
        return orderItemId + ":" + node;
    }

    static boolean isAfterSalesType(String planType) {
        return TYPE_AFTER_SALES_REWORK.equals(planType) || TYPE_AFTER_SALES_REPLACEMENT.equals(planType);
    }

    private void requireNormalType(String planType, List<ApiFieldError> errors) {
        if (planType == null || planType.isBlank()) {
            errors.add(new ApiFieldError("planType", "计划类型必填"));
            return;
        }
        if (!TYPE_NORMAL.equals(planType)) {
            errors.add(new ApiFieldError("planType", "本接口只创建正常计划；返工/重做/超额计划请从各自来源创建"));
        }
    }

    private String requireNode(String node, List<ApiFieldError> errors) {
        if (node == null || !ProductionNodes.isNode(node)) {
            errors.add(new ApiFieldError("node", "工序必须是 MAKING/PACKING_BAG/SEAM_CUTTING"));
            return null;
        }
        return node;
    }

    private int requirePositiveQuantity(Integer quantity, List<ApiFieldError> errors) {
        if (quantity == null || quantity < 1) {
            errors.add(new ApiFieldError("quantity", "计划数量必须大于 0"));
            return 0;
        }
        return quantity;
    }

    private OrderProductionReference.OrderItemContext requireItem(Long orderItemId, List<ApiFieldError> errors) {
        if (orderItemId == null) {
            errors.add(new ApiFieldError("orderItemId", "订单明细必填"));
            return null;
        }
        var context = reference.item(orderItemId);
        if (context == null) {
            errors.add(new ApiFieldError("orderItemId", "订单明细不存在"));
            return null;
        }
        if (!ORDER_CONFIRMED.equals(context.status())) {
            errors.add(new ApiFieldError("orderItemId", "仅已确认订单可以排产"));
            return null;
        }
        return context;
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}
