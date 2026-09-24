package com.yumi.orders.change;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.calculation.order.OrderPricing;
import com.yumi.identity.AuditContext;
import com.yumi.orders.change.internal.OrderChangeItemRow;
import com.yumi.orders.change.internal.OrderChangeRepository;
import com.yumi.orders.change.internal.OrderChangeRow;
import com.yumi.orders.fulfillment.internal.FulfillmentRepository;
import com.yumi.orders.order.OrderItemRequest;
import com.yumi.orders.order.OrderService;
import com.yumi.orders.order.OrderViews;
import com.yumi.orders.order.internal.OrderItemResolver;
import com.yumi.orders.order.internal.OrderItemRow;
import com.yumi.orders.order.internal.OrderRepository;
import com.yumi.orders.order.internal.OrderRow;
import com.yumi.orders.order.internal.OrderSnapshotRepository;
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
 * 订单变更应用服务（任务 3.7/3.8）：已确认订单只能通过变更单修改。
 * 草稿阶段可自由编辑；确认时在单事务内校验减单不变量、应用明细增改移除、
 * 写 ORDER_CHANGE 履约事实、同步需求投影、回写表头与重算金额，并把变更单置为已确认。
 * 超出在制/合格数量的处理方案（转成品余量/立即报废）落库为结构化决策，
 * 其对在制数量的执行事实由阶段五按方案执行（阶段三只记决策与需求/金额事实）。
 */
@Service
public class OrderChangeService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String ADD = "ADD";
    private static final String UPDATE = "UPDATE";
    private static final String REMOVE = "REMOVE";

    private final OrderChangeRepository changeRepository;
    private final OrderRepository orderRepository;
    private final OrderSnapshotRepository snapshotRepository;
    private final FulfillmentRepository fulfillmentRepository;
    private final OrderItemResolver itemResolver;
    private final OrderService orderService;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public OrderChangeService(OrderChangeRepository changeRepository, OrderRepository orderRepository,
                              OrderSnapshotRepository snapshotRepository,
                              FulfillmentRepository fulfillmentRepository,
                              OrderItemResolver itemResolver, OrderService orderService,
                              SequenceAllocator sequenceAllocator, AuditContext auditContext) {
        this.changeRepository = changeRepository;
        this.orderRepository = orderRepository;
        this.snapshotRepository = snapshotRepository;
        this.fulfillmentRepository = fulfillmentRepository;
        this.itemResolver = itemResolver;
        this.orderService = orderService;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    // ---------- 查询 ----------

    public OrderChangeViews.ChangeView get(long changeId) {
        var change = requireChange(changeId);
        var order = requireOrder(change.orderId());
        return toView(change, order.orderNo(), changeRepository.findItems(changeId));
    }

    public List<OrderChangeViews.ChangeView> listByOrder(long orderId) {
        var order = requireOrder(orderId);
        return changeRepository.findByOrder(orderId).stream()
                .map(change -> toView(change, order.orderNo(), changeRepository.findItems(change.id())))
                .toList();
    }

    // ---------- 变更草稿 ----------

    @Transactional
    public OrderChangeViews.ChangeView create(long orderId, CreateChangeOrderRequest request) {
        var order = requireChangeableOrder(orderId);
        if (changeRepository.findDraftByOrder(orderId).isPresent()) {
            throw new ApiException(ErrorCode.STATE_NOT_CHANGEABLE, "该订单已有未确认的变更草稿",
                    List.of(new ApiFieldError("orderId", "已存在变更草稿，请先确认或作废")));
        }
        var errors = new ArrayList<ApiFieldError>();
        var items = buildItems(order, request.items(), errors);
        failIfInvalid(errors);

        var audit = auditContext.current();
        var row = new OrderChangeRow(null,
                SequenceAllocator.format("CO", sequenceAllocator.next("order_changes")),
                orderId, STATUS_DRAFT, request.reason(), request.expectedDeliveryDate(),
                request.recipientName(), request.recipientPhone(), request.region(), request.address(),
                request.note(), request.discountAmount() == null ? null : DecimalPolicy.money(request.discountAmount()),
                0L);
        long changeId = changeRepository.insertChange(row, audit.requestId(), audit.idempotencyKey());
        changeRepository.replaceItems(changeId, items, audit.requestId());
        return get(changeId);
    }

    @Transactional
    public OrderChangeViews.ChangeView update(long changeId, CreateChangeOrderRequest request) {
        var change = requireChange(changeId);
        if (!STATUS_DRAFT.equals(change.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CHANGEABLE);
        }
        var order = requireChangeableOrder(change.orderId());
        var errors = new ArrayList<ApiFieldError>();
        var items = buildItems(order, request.items(), errors);
        failIfInvalid(errors);

        var audit = auditContext.current();
        var updated = new OrderChangeRow(change.id(), change.changeNo(), change.orderId(), change.status(),
                request.reason() != null ? request.reason() : change.reason(),
                request.expectedDeliveryDate() != null ? request.expectedDeliveryDate()
                        : change.newExpectedDeliveryDate(),
                orDefault(request.recipientName(), change.newRecipientName()),
                orDefault(request.recipientPhone(), change.newRecipientPhone()),
                orDefault(request.region(), change.newRegion()),
                orDefault(request.address(), change.newAddress()),
                orDefault(request.note(), change.newNote()),
                request.discountAmount() != null ? DecimalPolicy.money(request.discountAmount())
                        : change.newDiscountAmount(),
                change.version());
        changeRepository.updateChange(updated, audit.requestId());
        if (request.items() != null) {
            changeRepository.replaceItems(changeId, items, audit.requestId());
        }
        return get(changeId);
    }

    // ---------- 变更确认 ----------

    @Transactional
    public OrderChangeViews.ConfirmResult confirm(long changeId) {
        var change = requireChange(changeId);
        if (!STATUS_DRAFT.equals(change.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CHANGEABLE);
        }
        var order = requireChangeableOrder(change.orderId());
        var changeItems = changeRepository.findItems(changeId);
        var orderItems = orderRepository.findItems(order.id());
        var balances = new LinkedHashMap<Long, FulfillmentRepository.BalanceRow>();
        fulfillmentRepository.findBalances(order.id())
                .forEach(balance -> balances.put(balance.orderItemId(), balance));

        requireReductionInvariants(changeItems, orderItems, balances);

        var audit = auditContext.current();
        var errors = new ArrayList<ApiFieldError>();
        int nextLineNo = orderItems.stream().mapToInt(OrderItemRow::lineNo).max().orElse(0) + 1;
        for (var item : changeItems) {
            switch (item.changeType()) {
                case ADD -> nextLineNo = applyAdd(order, item, nextLineNo, change.id(), audit, errors);
                case UPDATE -> applyUpdate(order, item, orderItems, change.id(), audit, errors);
                case REMOVE -> applyRemove(order, item, orderItems, change.id(), audit, errors);
                default -> errors.add(new ApiFieldError("items", "未知的变更类型 " + item.changeType()));
            }
        }
        failIfInvalid(errors);

        var newDiscount = change.newDiscountAmount() != null ? change.newDiscountAmount() : order.discountAmount();
        var updatedItems = orderRepository.findItems(order.id());
        var totals = totalsOf(updatedItems, newDiscount);
        var delivery = change.newExpectedDeliveryDate() != null
                ? change.newExpectedDeliveryDate() : order.expectedDeliveryDate();
        if (delivery != null && delivery.isBefore(order.orderDate())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "交期不得早于下单日期",
                    List.of(new ApiFieldError("expectedDeliveryDate", "交期不得早于下单日期")));
        }
        var header = new OrderRow(order.id(), order.orderNo(), order.customerId(), order.customerName(),
                order.status(), order.orderDate(), delivery,
                orDefault(change.newRecipientName(), order.recipientName()),
                orDefault(change.newRecipientPhone(), order.recipientPhone()),
                orDefault(change.newRegion(), order.region()),
                orDefault(change.newAddress(), order.address()),
                orDefault(change.newNote(), order.note()),
                totals.goodsAmount(), totals.seamAmount(), totals.discountAmount(), totals.receivableAmount(),
                totals.goodsCostAmount(), totals.seamCostAmount(), totals.costAmount(), totals.profitAmount(),
                order.version());
        if (snapshotRepository.applyChangeHeader(header, order.version(), audit.requestId()) == 0) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
        if (changeRepository.markConfirmed(changeId, change.version(), audit.adminUsername(),
                audit.requestId()) == 0) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
        return new OrderChangeViews.ConfirmResult(get(changeId), orderService.get(order.id()));
    }

    // ---------- 应用单条变更 ----------

    private int applyAdd(OrderRow order, OrderChangeItemRow item, int lineNo, long changeId,
                         AuditContext.Context audit, List<ApiFieldError> errors) {
        var resolved = itemResolver.resolveOne(new OrderItemRequest(item.productId(), item.afterQuantity(),
                item.afterSeamQuantity(), item.afterUnitPrice(), item.afterSeamTypeId(), item.afterSeamFee(),
                item.afterNote()), lineNo, "items[" + lineNo + "]", null, true, errors);
        if (resolved == null) {
            return lineNo;
        }
        orderRepository.insertItem(withOrder(resolved.row(), order.id()), audit.requestId());
        long newItemId = orderRepository.findItemId(order.id(), lineNo);
        writeDemandFact(order, newItemId, changeId, "IN", resolved.row().quantity(), audit);
        fulfillmentRepository.insertBalance(order.id(), newItemId, resolved.row().quantity(), audit.requestId());
        return lineNo + 1;
    }

    private void applyUpdate(OrderRow order, OrderChangeItemRow item, List<OrderItemRow> orderItems,
                             long changeId, AuditContext.Context audit, List<ApiFieldError> errors) {
        var current = requireItem(orderItems, item);
        var resolved = itemResolver.resolveOne(new OrderItemRequest(item.productId(), item.afterQuantity(),
                item.afterSeamQuantity(), item.afterUnitPrice(), item.afterSeamTypeId(), item.afterSeamFee(),
                item.afterNote()), current.lineNo(), "items[" + (current.lineNo() - 1) + "]", current, true, errors);
        if (resolved == null) {
            return;
        }
        orderRepository.updateItem(resolved.row(), audit.requestId());
        int delta = resolved.row().quantity() - current.quantity();
        if (delta > 0) {
            writeDemandFact(order, current.id(), changeId, "IN", delta, audit);
        } else if (delta < 0) {
            writeDemandFact(order, current.id(), changeId, "OUT", -delta, audit);
        }
        fulfillmentRepository.updateRequiredQuantity(current.id(), resolved.row().quantity(), audit.requestId());
    }

    private void applyRemove(OrderRow order, OrderChangeItemRow item, List<OrderItemRow> orderItems,
                             long changeId, AuditContext.Context audit, List<ApiFieldError> errors) {
        var current = requireItem(orderItems, item);
        // 移除＝数量归零：保留行、快照与履约历史，不物理删除
        var resolved = itemResolver.resolveOne(new OrderItemRequest(current.productId(), 0, 0,
                current.unitPrice(), null, null, current.note()), current.lineNo(),
                "items[" + (current.lineNo() - 1) + "]", current, false, errors);
        if (resolved == null) {
            return;
        }
        orderRepository.updateItem(resolved.row(), audit.requestId());
        writeDemandFact(order, current.id(), changeId, "OUT", current.quantity(), audit);
        fulfillmentRepository.updateRequiredQuantity(current.id(), 0, audit.requestId());
    }

    // ---------- 减单不变量（任务 3.8） ----------

    /** 新有效数量不得低于累计有效发货；超出在制/合格数量必须逐项提交处理方案。 */
    private void requireReductionInvariants(List<OrderChangeItemRow> changeItems, List<OrderItemRow> orderItems,
                                           java.util.Map<Long, FulfillmentRepository.BalanceRow> balances) {
        for (var item : changeItems) {
            if (ADD.equals(item.changeType())) {
                continue;
            }
            var current = requireItem(orderItems, item);
            int target = REMOVE.equals(item.changeType()) ? 0
                    : item.afterQuantity() != null ? item.afterQuantity() : current.quantity();
            var balance = balances.get(current.id());
            int shipped = balance == null ? 0 : balance.shippedQuantity();
            if (target < shipped) {
                throw new ApiException(ErrorCode.QUANTITY_BELOW_SHIPPED,
                        ErrorCode.QUANTITY_BELOW_SHIPPED.defaultMessage(),
                        List.of(new ApiFieldError("items[" + (current.lineNo() - 1) + "].quantity",
                                "新数量 " + target + " 不得低于累计有效发货 " + shipped)));
            }
            int inProcess = balance == null ? 0
                    : balance.makingInflow() + balance.packingInflow() + balance.seamInflow();
            int surplus = Math.max(0, inProcess - target);
            if (surplus > 0 && !hasDisposition(item, surplus)) {
                throw new ApiException(ErrorCode.QUANTITY_REQUIRES_DISPOSITION,
                        ErrorCode.QUANTITY_REQUIRES_DISPOSITION.defaultMessage(),
                        List.of(new ApiFieldError("items[" + (current.lineNo() - 1) + "].surplusDisposition",
                                "超出在制/合格 " + surplus + " 件，必须逐项选择转成品余量或立即报废并填写数量与原因")));
            }
        }
    }

    private static boolean hasDisposition(OrderChangeItemRow item, int surplus) {
        var disposition = item.surplusDisposition();
        if (disposition == null
                || !("FINISH_TO_SURPLUS".equals(disposition) || "SCRAP".equals(disposition))) {
            return false;
        }
        if (item.surplusQuantity() == null || item.surplusQuantity() < surplus) {
            return false;
        }
        return item.surplusReason() != null && !item.surplusReason().isBlank();
    }

    // ---------- 草稿明细构建 ----------

    private List<OrderChangeItemRow> buildItems(OrderRow order, List<OrderChangeItemRequest> requests,
                                               List<ApiFieldError> errors) {
        if (requests == null) {
            return List.of();
        }
        var orderItems = orderRepository.findItems(order.id());
        var rows = new ArrayList<OrderChangeItemRow>();
        for (int index = 0; index < requests.size(); index++) {
            var request = requests.get(index);
            var prefix = "items[" + index + "]";
            if (request.orderItemId() == null) {
                if (request.productId() == null) {
                    errors.add(new ApiFieldError(prefix + ".productId", "新增明细必须指定商品"));
                    continue;
                }
                rows.add(new OrderChangeItemRow(null, 0L, null, ADD, null, request.productId(),
                        null, request.quantity(), null, request.seamQuantity(),
                        null, money(request.unitPrice()), null, request.seamTypeId(),
                        null, money(request.seamFee()), null, request.note(),
                        request.surplusDisposition(), request.surplusQuantity(), request.surplusReason()));
                continue;
            }
            var current = orderItems.stream()
                    .filter(row -> row.id().equals(request.orderItemId())).findFirst().orElse(null);
            if (current == null) {
                errors.add(new ApiFieldError(prefix + ".orderItemId", "明细不存在"));
                continue;
            }
            var remove = Boolean.TRUE.equals(request.remove());
            rows.add(new OrderChangeItemRow(null, 0L, current.id(), remove ? REMOVE : UPDATE, current.lineNo(),
                    current.productId(),
                    current.quantity(), remove ? null : orDefault(request.quantity(), current.quantity()),
                    current.seamQuantity(), remove ? null : orDefault(request.seamQuantity(), current.seamQuantity()),
                    current.unitPrice(), remove ? null : money(orDefault(request.unitPrice(), current.unitPrice())),
                    current.seamTypeId(), remove ? null : orDefault(request.seamTypeId(), current.seamTypeId()),
                    current.seamFee(), remove ? null : money(orDefault(request.seamFee(), current.seamFee())),
                    current.note(), remove ? null : orDefault(request.note(), current.note()),
                    request.surplusDisposition(), request.surplusQuantity(), request.surplusReason()));
        }
        return rows;
    }

    // ---------- 内部工具 ----------

    private OrderRow requireChangeableOrder(long orderId) {
        var order = requireOrder(orderId);
        if (!STATUS_CONFIRMED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_CHANGEABLE, "仅已确认订单可变更",
                    List.of(new ApiFieldError("orderId", "当前状态不可变更，请直接编辑草稿")));
        }
        return order;
    }

    private OrderChangeRow requireChange(long changeId) {
        return changeRepository.findById(changeId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "变更单不存在"));
    }

    private OrderRow requireOrder(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORDER_NOT_FOUND));
    }

    private static OrderItemRow requireItem(List<OrderItemRow> orderItems, OrderChangeItemRow item) {
        return orderItems.stream().filter(row -> row.id().equals(item.orderItemId())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_INVALID, "明细不存在",
                        List.of(new ApiFieldError("orderItemId", "明细不存在"))));
    }

    private void writeDemandFact(OrderRow order, long orderItemId, long changeId, String direction,
                                 int quantity, AuditContext.Context audit) {
        fulfillmentRepository.insertEntry(order.id(), orderItemId, "ORDER_CHANGE", "SHIPPABLE", direction,
                quantity, "ORDER_CHANGE", changeId, orderItemId, LocalDate.now(),
                audit.adminUsername(), "订单变更需求调整", audit.requestId());
    }

    private static OrderItemRow withOrder(OrderItemRow row, long orderId) {
        return new OrderItemRow(row.id(), orderId, row.lineNo(), row.productId(), row.productNo(),
                row.productName(), row.quantity(), row.seamQuantity(), row.unitPrice(), row.goodsAmount(),
                row.seamTypeId(), row.seamTypeName(), row.seamUnitCost(), row.seamFee(), row.seamAmount(),
                row.unitCost(), row.goodsCostAmount(), row.seamCostAmount(), row.note(), row.version());
    }

    private static OrderPricing.OrderTotals totalsOf(List<OrderItemRow> items, BigDecimal discount) {
        try {
            return OrderPricing.totals(items.stream()
                    .map(item -> new OrderPricing.ItemResult(item.goodsAmount(), item.seamAmount(),
                            item.goodsCostAmount(), item.seamCostAmount()))
                    .toList(), discount);
        } catch (IllegalArgumentException outOfRange) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "整单优惠不合法",
                    List.of(new ApiFieldError("discountAmount", "优惠必须在 0 与商品金额加缝边收费之间")));
        }
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : DecimalPolicy.money(value);
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static OrderChangeViews.ChangeView toView(OrderChangeRow change, String orderNo,
                                                     List<OrderChangeItemRow> items) {
        return new OrderChangeViews.ChangeView(change.id(), change.changeNo(), change.orderId(), orderNo,
                change.status(), change.reason(), change.newExpectedDeliveryDate(), change.newRecipientName(),
                change.newRecipientPhone(), change.newRegion(), change.newAddress(), change.newNote(),
                change.newDiscountAmount(), change.version(), items.stream().map(item ->
                new OrderChangeViews.ChangeItemView(item.id(), item.orderItemId(), item.changeType(),
                        item.lineNo(), item.productId(), item.beforeQuantity(), item.afterQuantity(),
                        item.beforeSeamQuantity(), item.afterSeamQuantity(), item.beforeUnitPrice(),
                        item.afterUnitPrice(), item.beforeSeamTypeId(), item.afterSeamTypeId(),
                        item.beforeSeamFee(), item.afterSeamFee(), item.beforeNote(), item.afterNote(),
                        item.surplusDisposition(), item.surplusQuantity(), item.surplusReason())).toList());
    }
}
