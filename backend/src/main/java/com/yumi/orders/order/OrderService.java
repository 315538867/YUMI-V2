package com.yumi.orders.order;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.calculation.order.OrderPricing;
import com.yumi.identity.AuditContext;
import com.yumi.orders.order.internal.OrderItemRow;
import com.yumi.orders.order.internal.InventoryPlanReference;
import com.yumi.orders.order.internal.OrderItemResolver;
import com.yumi.orders.order.internal.OrderPlanLineRow;
import com.yumi.orders.order.internal.OrderReference;
import com.yumi.orders.fulfillment.FulfillmentService;
import com.yumi.orders.fulfillment.OrderStatuses;
import com.yumi.orders.order.internal.OrderRepository;
import com.yumi.orders.order.internal.OrderRow;
import com.yumi.orders.order.internal.OrderSnapshotRepository;
import com.yumi.orders.fulfillment.internal.FulfillmentRepository;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 订单应用服务（任务 3.2/3.3）：草稿创建、查询与编辑。
 * 明细识别信息与单件成本一律由服务端读取商品，成交价/缝边收费可覆盖但金额整体重算；
 * 草稿明细按整单替换维护，已确认后的变更由订单变更单负责（任务 3.7）。
 */
@Service
public class OrderService {

    static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String PRODUCT_ACTIVE = "ACTIVE";

    private final OrderRepository repository;
    private final OrderReference reference;
    private final OrderItemResolver itemResolver;
    private final FulfillmentService fulfillmentService;
    private final InventoryPlanReference inventoryPlanReference;
    private final OrderSnapshotRepository snapshotRepository;
    private final FulfillmentRepository fulfillmentRepository;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public OrderService(OrderRepository repository, OrderReference reference,
                        OrderItemResolver itemResolver, FulfillmentService fulfillmentService,
                        InventoryPlanReference inventoryPlanReference,
                        OrderSnapshotRepository snapshotRepository,
                        FulfillmentRepository fulfillmentRepository,
                        SequenceAllocator sequenceAllocator, AuditContext auditContext) {
        this.repository = repository;
        this.reference = reference;
        this.itemResolver = itemResolver;
        this.fulfillmentService = fulfillmentService;
        this.inventoryPlanReference = inventoryPlanReference;
        this.snapshotRepository = snapshotRepository;
        this.fulfillmentRepository = fulfillmentRepository;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    // ---------- 查询 ----------

    public List<OrderViews.OrderSummary> list(String status, Long customerId, LocalDate from, LocalDate to) {
        var derivedByOrder = fulfillmentService.derivedByOrder();
        return repository.find(status, customerId, from, to).stream()
                .map(row -> toSummary(row, derivedByOrder.get(row.id())))
                .toList();
    }

    public OrderViews.OrderDetail get(long id) {
        var row = requireOrder(id);
        return toDetail(row, repository.findItems(id), fulfillmentService.derivedFor(row), planLines(id));
    }

    /** 取消订单（任务 3.9）：草稿可直接取消；已确认仅在无执行类履约事实时可取消，不删除历史。 */
    @Transactional
    public OrderViews.OrderDetail cancel(long id, String reason) {
        var order = requireOrder(id);
        if (STATUS_DRAFT.equals(order.status())) {
            // 草稿可直接取消
        } else if (STATUS_CONFIRMED.equals(order.status())) {
            if (fulfillmentRepository.countExecutionFacts(id) > 0) {
                throw new ApiException(ErrorCode.STATE_CANCEL_NOT_ALLOWED,
                        "订单已有履约事实，请通过订单变更处理剩余需求与超出数量",
                        List.of(new ApiFieldError("orderId", "已有生产/库存/发货等事实，不能直接取消")));
            }
        } else {
            throw new ApiException(ErrorCode.STATE_CANCEL_NOT_ALLOWED);
        }
        var audit = auditContext.current();
        snapshotRepository.markCancelled(id, audit.adminUsername(), reason, audit.requestId());
        return get(id);
    }

    // ---------- 创建 ----------

    @Transactional
    public OrderViews.OrderDetail create(CreateOrderRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        var customer = resolveCustomer(request.customerId(), errors);
        var orderDate = requireOrderDate(request.orderDate(), errors);
        var deliveryDate = validateDeliveryDate(request.expectedDeliveryDate(), orderDate, errors);
        var items = resolveItems(request.items(), errors);
        if (errors.isEmpty()) {
            requireDiscountInRange(request.discountAmount(), items, errors);
            validatePlan(request.inventoryPlan(), items.size(), errors);
        }
        failIfInvalid(errors);

        var audit = auditContext.current();
        var totals = totalsOf(items, request.discountAmount());
        var row = new OrderRow(null,
                SequenceAllocator.format("YM", sequenceAllocator.next("orders")),
                customer.id(), customer.name(), STATUS_DRAFT, orderDate, deliveryDate,
                orDefault(request.recipientName(), customer.defaultRecipient()),
                orDefault(request.recipientPhone(), customer.defaultRecipientPhone()),
                orDefault(request.region(), customer.defaultRegion()),
                orDefault(request.address(), customer.defaultAddress()),
                request.note(),
                totals.goodsAmount(), totals.seamAmount(), totals.discountAmount(), totals.receivableAmount(),
                totals.goodsCostAmount(), totals.seamCostAmount(), totals.costAmount(), totals.profitAmount(),
                0L);
        long id = repository.insertOrder(row, audit.requestId(), audit.idempotencyKey());
        persistItems(id, items, audit.requestId());
        persistPlan(id, request.inventoryPlan(), audit.requestId());
        return get(id);
    }

    // ---------- 编辑草稿 ----------

    @Transactional
    public OrderViews.OrderDetail update(long id, UpdateOrderRequest request) {
        var existing = requireOrder(id);
        var errors = new ArrayList<ApiFieldError>();
        if (request.version() == null) {
            errors.add(new ApiFieldError("version", "缺少版本号"));
        } else if (request.version() != existing.version()) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
        if (!STATUS_DRAFT.equals(existing.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE);
        }

        var customerId = request.customerId() != null ? request.customerId() : existing.customerId();
        var customer = resolveCustomer(customerId, errors);
        var orderDate = request.orderDate() != null ? request.orderDate() : existing.orderDate();
        var deliveryDate = Boolean.TRUE.equals(request.clearExpectedDeliveryDate())
                ? null
                : request.expectedDeliveryDate() != null ? request.expectedDeliveryDate()
                        : existing.expectedDeliveryDate();
        deliveryDate = validateDeliveryDate(deliveryDate, orderDate, errors);
        var items = request.items() != null ? resolveItems(request.items(), errors)
                : resolveExistingItems(repository.findItems(id), errors);
        var discount = request.discountAmount() != null ? request.discountAmount() : existing.discountAmount();
        if (errors.isEmpty()) {
            requireDiscountInRange(discount, items, errors);
            if (request.inventoryPlan() != null) {
                validatePlan(request.inventoryPlan(), items.size(), errors);
            }
        }
        failIfInvalid(errors);

        var audit = auditContext.current();
        var totals = totalsOf(items, discount);
        var updated = new OrderRow(existing.id(), existing.orderNo(), customerId, customer.name(),
                existing.status(), orderDate, deliveryDate,
                orDefault(request.recipientName(), existing.recipientName()),
                orDefault(request.recipientPhone(), existing.recipientPhone()),
                orDefault(request.region(), existing.region()),
                orDefault(request.address(), existing.address()),
                orDefault(request.note(), existing.note()),
                totals.goodsAmount(), totals.seamAmount(), totals.discountAmount(), totals.receivableAmount(),
                totals.goodsCostAmount(), totals.seamCostAmount(), totals.costAmount(), totals.profitAmount(),
                existing.version());
        repository.updateOrder(updated, audit.requestId());
        if (request.items() != null) {
            // 计划行对明细有外键，且明细替换后原计划行引用的明细已不存在，必须先清计划再删明细
            repository.deletePlanLines(id);
            repository.deleteItems(id);
            persistItems(id, items, audit.requestId());
        }
        if (request.inventoryPlan() != null) {
            repository.deletePlanLines(id);
            persistPlan(id, request.inventoryPlan(), audit.requestId());
        }
        return get(id);
    }

    // ---------- 确认 ----------

    /**
     * 确认订单并重验草稿库存计划（任务 4.8）：计划不占用库存，确认时按批次聚合重验余额；
     * 有缺口且未明确确认转生产时返回每批缺口（STOCK_INSUFFICIENT），**不自动补缺**。
     * 确认本身不创建领用事实，实际领用仍由库存领用命令产生。
     */
    @Transactional
    public OrderViews.OrderDetail confirm(long id, boolean transferShortageToProduction) {
        requirePlanAvailable(id, transferShortageToProduction);
        return doConfirm(id);
    }

    /** 计划缺口重验：管理员明确转生产时跳过；缺口消息按批次聚合，避免逐行比较漏判总量超支。 */
    private void requirePlanAvailable(long id, boolean transferShortageToProduction) {
        if (transferShortageToProduction) {
            return;
        }
        var planned = repository.findPlanLines(id);
        if (planned.isEmpty()) {
            return;
        }
        var plannedByBatch = new LinkedHashMap<Long, Integer>();
        for (var line : planned) {
            plannedByBatch.merge(line.batchId(), line.quantity(), Integer::sum);
        }
        var gaps = inventoryPlanReference.gaps(plannedByBatch);
        if (!gaps.isEmpty()) {
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT,
                    "草稿库存计划余额不足，请重新选择批次或明确将缺口转生产",
                    gaps.stream().map(gap -> new ApiFieldError("inventoryPlan", gap)).toList());
        }
    }

    /**
     * 确认订单（任务 3.4）：单事务内重验商品、订购数量/缝边数量、交期与金额自洽，写入订单级/明细级快照，
     * 建立履约需求事实与投影，并把订单置为已确认。不自动创建生产计划。
     * 重复幂等键由幂等过滤器返回首次结果，不重复生成快照与履约来源。
     */
    private OrderViews.OrderDetail doConfirm(long id) {
        var order = requireOrder(id);
        if (!STATUS_DRAFT.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CONFIRMABLE);
        }
        var items = repository.findItems(id);
        var errors = new ArrayList<ApiFieldError>();
        if (items.isEmpty()) {
            errors.add(new ApiFieldError("items", "至少一条明细"));
        }
        validateDeliveryDate(order.expectedDeliveryDate(), order.orderDate(), errors);

        var products = new LinkedHashMap<Long, OrderReference.Product>();
        for (var item : items) {
            var prefix = "items[" + (item.lineNo() - 1) + "]";
            var product = reference.product(item.productId()).orElse(null);
            if (product == null) {
                errors.add(new ApiFieldError(prefix + ".productId", "商品不存在"));
                continue;
            }
            if (!PRODUCT_ACTIVE.equals(product.status())) {
                errors.add(new ApiFieldError(prefix + ".productId", "商品已停用"));
                continue;
            }
            if (item.seamQuantity() > item.quantity()) {
                errors.add(new ApiFieldError(prefix + ".seamQuantity", "缝边数量必须在 0 与明细数量之间"));
            }
            if (DecimalPolicy.money(product.totalCost()).compareTo(item.unitCost()) != 0) {
                errors.add(new ApiFieldError(prefix + ".unitCost", "商品成本已变化，请重新保存草稿后再确认"));
                continue;
            }
            products.put(item.id(), product);
        }
        requireAmountsConsistent(order, items, errors);
        failIfInvalid(errors);

        var audit = auditContext.current();
        for (var item : items) {
            snapshotRepository.insertItemSnapshot(order, item, products.get(item.id()),
                    flowOf(item), audit.requestId());
            fulfillmentRepository.insertEntry(order.id(), item.id(), "ORDER_DEMAND", "SHIPPABLE", "IN",
                    item.quantity(), "ORDER", order.id(), item.id(), order.orderDate(),
                    audit.adminUsername(), "订单确认需求", audit.requestId());
            fulfillmentRepository.insertBalance(order.id(), item.id(), item.quantity(), audit.requestId());
        }
        snapshotRepository.insertOrderSnapshot(order, requireCustomer(order.customerId()),
                audit.adminUsername(), audit.requestId());
        if (snapshotRepository.markConfirmed(order.id(), order.version(), audit.adminUsername(),
                audit.requestId()) == 0) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
        return get(id);
    }

    /** 冻结流程文本：缝边剪袋只对缝边数量>0 的明细出现。 */
    private static String flowOf(OrderItemRow item) {
        return item.seamQuantity() > 0
                ? "制作 → 捏毛装袋 → 缝边剪袋 → 可发货"
                : "制作 → 捏毛装袋 → 可发货";
    }

    /** 金额自洽：由明细重算的汇总必须与订单表头逐项一致（防部分写入与篡改）。 */
    private static void requireAmountsConsistent(OrderRow order, List<OrderItemRow> items,
                                                List<ApiFieldError> errors) {
        var amounts = items.stream()
                .map(item -> new OrderPricing.ItemResult(item.goodsAmount(), item.seamAmount(),
                        item.goodsCostAmount(), item.seamCostAmount()))
                .toList();
        var totals = totalsOfAmounts(amounts, order.discountAmount());
        if (totals.goodsAmount().compareTo(order.goodsAmount()) != 0
                || totals.seamAmount().compareTo(order.seamAmount()) != 0
                || totals.receivableAmount().compareTo(order.receivableAmount()) != 0
                || totals.costAmount().compareTo(order.costAmount()) != 0
                || totals.profitAmount().compareTo(order.profitAmount()) != 0) {
            errors.add(new ApiFieldError("amounts", "订单金额与明细不一致，请重新保存草稿"));
        }
    }

    private OrderReference.Customer requireCustomer(long customerId) {
        return reference.customer(customerId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_INVALID, "客户不存在",
                        List.of(new ApiFieldError("customerId", "客户不存在"))));
    }

    // ---------- 内部工具 ----------

    private OrderRow requireOrder(long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
    }

    private OrderReference.Customer resolveCustomer(Long customerId, List<ApiFieldError> errors) {
        if (customerId == null) {
            errors.add(new ApiFieldError("customerId", "客户必填"));
            return null;
        }
        var customer = reference.customer(customerId).orElse(null);
        if (customer == null) {
            errors.add(new ApiFieldError("customerId", "客户不存在"));
        }
        return customer;
    }

    private static LocalDate requireOrderDate(LocalDate orderDate, List<ApiFieldError> errors) {
        if (orderDate == null) {
            errors.add(new ApiFieldError("orderDate", "下单日期必填"));
        }
        return orderDate;
    }

    private static LocalDate validateDeliveryDate(LocalDate deliveryDate, LocalDate orderDate,
                                                 List<ApiFieldError> errors) {
        if (deliveryDate != null && orderDate != null && deliveryDate.isBefore(orderDate)) {
            errors.add(new ApiFieldError("expectedDeliveryDate", "交期不得早于下单日期"));
        }
        return deliveryDate;
    }

    /** 草稿明细解析：与订单变更新增行共用 {@link OrderItemResolver}。 */
    private List<OrderItemResolver.Resolved> resolveItems(List<OrderItemRequest> requests,
                                                         List<ApiFieldError> errors) {
        return itemResolver.resolve(requests, 1, true, errors);
    }

    /** 编辑未替换明细时按现有行重算（商品成本可能已变，与商品侧“保存时重算”一致）。 */
    private List<OrderItemResolver.Resolved> resolveExistingItems(List<OrderItemRow> rows,
                                                                 List<ApiFieldError> errors) {
        var resolved = new ArrayList<OrderItemResolver.Resolved>();
        for (var row : rows) {
            var request = new OrderItemRequest(row.productId(), row.quantity(), row.seamQuantity(),
                    row.unitPrice(), row.seamTypeId(), row.seamFee(), row.note());
            var item = itemResolver.resolveOne(request, row.lineNo(), "items[" + (row.lineNo() - 1) + "]",
                    row, true, errors);
            if (item != null) {
                resolved.add(item);
            }
        }
        return resolved;
    }

    /** 优惠范围由集中计算模块裁定，这里只把它映射成字段级 400。 */
    private static void requireDiscountInRange(BigDecimal discount, List<OrderItemResolver.Resolved> items,
                                              List<ApiFieldError> errors) {
        if (discount == null) {
            return;
        }
        try {
            OrderPricing.totals(amountsOf(items), discount);
        } catch (IllegalArgumentException outOfRange) {
            errors.add(new ApiFieldError("discountAmount", "优惠必须在 0 与商品金额加缝边收费之间"));
        }
    }

    /**
     * 草稿库存计划校验（任务 4.8）：序号须落在本次明细范围内、批次须存在、数量为正、
     * 同明细同批次不重复。计划只是参考，因此不校验总量是否超过明细需求，也不占用库存。
     */
    private void validatePlan(List<OrderPlanLineRequest> plan, int itemCount, List<ApiFieldError> errors) {
        if (plan == null || plan.isEmpty()) {
            return;
        }
        var seenTargets = new java.util.HashSet<String>();
        for (int i = 0; i < plan.size(); i++) {
            var line = plan.get(i);
            var prefix = "inventoryPlan[" + i + "]";
            if (line.lineNo() == null || line.lineNo() < 1 || line.lineNo() > itemCount) {
                errors.add(new ApiFieldError(prefix + ".lineNo", "明细序号必须在 1 与明细数量之间"));
            }
            if (line.batchId() == null) {
                errors.add(new ApiFieldError(prefix + ".batchId", "批次必填"));
            } else if (inventoryPlanReference.batch(line.batchId()) == null) {
                errors.add(new ApiFieldError(prefix + ".batchId", "批次不存在"));
            }
            if (line.quantity() == null || line.quantity() < 1) {
                errors.add(new ApiFieldError(prefix + ".quantity", "计划数量必须大于 0"));
            }
            if (line.lineNo() != null && line.batchId() != null
                    && !seenTargets.add(line.lineNo() + ":" + line.batchId())) {
                errors.add(new ApiFieldError(prefix + ".batchId", "同一明细的同一批次只能有一条计划行"));
            }
        }
    }

    private static OrderPricing.OrderTotals totalsOf(List<OrderItemResolver.Resolved> items, BigDecimal discount) {
        return totalsOfAmounts(amountsOf(items), discount);
    }

    private static OrderPricing.OrderTotals totalsOfAmounts(List<OrderPricing.ItemResult> amounts,
                                                           BigDecimal discount) {
        try {
            return OrderPricing.totals(amounts, discount);
        } catch (IllegalArgumentException outOfRange) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "整单优惠不合法",
                    List.of(new ApiFieldError("discountAmount", "优惠必须在 0 与商品金额加缝边收费之间")));
        }
    }

    private static List<OrderPricing.ItemResult> amountsOf(List<OrderItemResolver.Resolved> items) {
        return items.stream().map(OrderItemResolver.Resolved::amounts).toList();
    }

    private void persistItems(long orderId, List<OrderItemResolver.Resolved> items, String requestId) {
        for (var item : items) {
            var row = item.row();
            repository.insertItem(new OrderItemRow(row.id(), orderId, row.lineNo(), row.productId(),
                    row.productNo(), row.productName(), row.quantity(), row.seamQuantity(),
                    row.unitPrice(), row.goodsAmount(), row.seamTypeId(), row.seamTypeName(),
                    row.seamUnitCost(), row.seamFee(), row.seamAmount(), row.unitCost(),
                    row.goodsCostAmount(), row.seamCostAmount(), row.note(), 0L), requestId);
        }
    }

    /** 计划行按明细序号落库：明细 id 由序号反查，避免依赖插入后回填的自增 id。 */
    private void persistPlan(long orderId, List<OrderPlanLineRequest> plan, String requestId) {
        if (plan == null || plan.isEmpty()) {
            return;
        }
        for (var line : plan) {
            repository.insertPlanLine(orderId, repository.findItemId(orderId, line.lineNo()),
                    line.batchId(), line.quantity(), requestId);
        }
    }

    /** 计划行读模型：补上批次当前工序、缝边状态与数量，页面据此判断计划是否已过期。 */
    private List<OrderViews.OrderPlanLineView> planLines(long orderId) {
        var rows = repository.findPlanLines(orderId);
        if (rows.isEmpty()) {
            return List.of();
        }
        var batches = inventoryPlanReference.batches(
                rows.stream().map(OrderPlanLineRow::batchId).distinct().toList());
        return rows.stream().map(line -> {
            var batch = batches.get(line.batchId());
            return new OrderViews.OrderPlanLineView(line.id(), line.orderItemId(), line.lineNo(), line.batchId(),
                    batch == null ? null : batch.batchNo(), batch == null ? null : batch.node(),
                    batch == null ? null : batch.seamState(), batch == null ? 0 : batch.quantity(),
                    line.quantity());
        }).toList();
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static OrderViews.OrderSummary toSummary(OrderRow row, OrderStatuses.Derived derived) {
        return new OrderViews.OrderSummary(row.id(), row.orderNo(), row.customerId(), row.customerName(),
                row.status(), row.orderDate(), row.expectedDeliveryDate(), row.receivableAmount(), row.version(),
                derived == null ? OrderStatuses.derive(new OrderStatuses.Quantities(0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                        : derived);
    }

    private static OrderViews.OrderDetail toDetail(OrderRow row, List<OrderItemRow> items,
                                                  OrderStatuses.Derived derived,
                                                  List<OrderViews.OrderPlanLineView> inventoryPlan) {
        return new OrderViews.OrderDetail(row.id(), row.orderNo(), row.customerId(), row.customerName(),
                row.status(), row.orderDate(), row.expectedDeliveryDate(),
                row.recipientName(), row.recipientPhone(), row.region(), row.address(), row.note(),
                row.goodsAmount(), row.seamAmount(), row.discountAmount(), row.receivableAmount(),
                row.goodsCostAmount(), row.seamCostAmount(), row.costAmount(), row.profitAmount(),
                row.version(), derived, items.stream().map(OrderService::toItemView).toList(), inventoryPlan);
    }

    private static OrderViews.OrderItemView toItemView(OrderItemRow row) {
        return new OrderViews.OrderItemView(row.id(), row.lineNo(), row.productId(), row.productNo(),
                row.productName(), row.quantity(), row.seamQuantity(), row.unitPrice(), row.goodsAmount(),
                row.seamTypeId(), row.seamTypeName(), row.seamUnitCost(), row.seamFee(), row.seamAmount(),
                row.unitCost(), row.goodsCostAmount(), row.seamCostAmount(), row.note());
    }

}
