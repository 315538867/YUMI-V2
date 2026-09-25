package com.yumi.inventory;

import com.yumi.identity.AuditContext;
import com.yumi.inventory.allocation.internal.InventoryAllocationLineRow;
import com.yumi.inventory.allocation.internal.InventoryAllocationRepository;
import com.yumi.inventory.allocation.internal.InventoryAllocationRow;
import com.yumi.inventory.batch.internal.InventoryBatchRepository;
import com.yumi.inventory.batch.internal.InventoryBatchRow;
import com.yumi.inventory.internal.InventoryReference;
import com.yumi.inventory.movement.internal.InventoryMovementLineRow;
import com.yumi.inventory.movement.internal.InventoryMovementRepository;
import com.yumi.inventory.movement.internal.InventoryMovementRow;
import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存应用服务（任务 4.2–4.5）：批次/汇总/流水查询、期初入库、盘点调整与领用推荐。
 * 数量只能由不可变流水改变：期初与盘点都先生成流水行、再按流水结果回写批次当前数量；
 * 期初不伪造历史订单、生产或工资事实。领用与冲销在 4.6–4.10 追加。
 */
@Service
public class InventoryService {

    static final String SOURCE_OPENING = "OPENING";
    static final String SOURCE_ADJUSTMENT = "ADJUSTMENT";
    private static final String TYPE_OPENING = "OPENING";
    private static final String TYPE_ADJUSTMENT = "ADJUSTMENT";
    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";
    private static final String PRODUCT_ACTIVE = "ACTIVE";
    private static final String TYPE_ALLOCATION = "ALLOCATION";
    private static final String TYPE_ALLOCATION_CANCEL = "ALLOCATION_CANCEL";
    private static final String TYPE_REVERSAL = "REVERSAL";
    private static final String SOURCE_INVENTORY = "INVENTORY";
    private static final String SOURCE_AFTER_SALES = "AFTER_SALES";
    private static final String TYPE_AFTER_SALES_ALLOCATION = "AFTER_SALES_ALLOCATION";
    private static final String SOURCE_INVENTORY_CANCEL = "INVENTORY_CANCEL";
    private static final String ORDER_CONFIRMED = "CONFIRMED";
    private static final String ALLOCATION_CONFIRMED = "CONFIRMED";
    private static final String ALLOCATION_CANCELLED = "CANCELLED";
    private static final String ENTRY_INVENTORY_ALLOCATION = "INVENTORY_ALLOCATION";

    private final InventoryBatchRepository batchRepository;
    private final InventoryMovementRepository movementRepository;
    private final InventoryReference reference;
    private final InventoryAllocationRepository allocationRepository;
    private final FulfillmentLedger fulfillmentLedger;
    private final com.yumi.orders.ledger.AfterSalesLedger afterSalesLedger;
    private final SequenceAllocator sequenceAllocator;
    private final AuditContext auditContext;

    public InventoryService(InventoryBatchRepository batchRepository,
                            InventoryMovementRepository movementRepository,
                            InventoryAllocationRepository allocationRepository,
                            InventoryReference reference,
                            FulfillmentLedger fulfillmentLedger,
                            com.yumi.orders.ledger.AfterSalesLedger afterSalesLedger,
                            SequenceAllocator sequenceAllocator,
                            AuditContext auditContext) {
        this.batchRepository = batchRepository;
        this.movementRepository = movementRepository;
        this.allocationRepository = allocationRepository;
        this.reference = reference;
        this.fulfillmentLedger = fulfillmentLedger;
        this.afterSalesLedger = afterSalesLedger;
        this.sequenceAllocator = sequenceAllocator;
        this.auditContext = auditContext;
    }

    // ---------- 领用（任务 4.6/4.7） ----------

    /**
     * 库存领用：同一事务内按批次 id 升序悲观锁、重校验余额与接入兼容性、生成出库流水、扣减库存，
     * 并通过订单侧结果接口登记履约接入事实与投影。库存不足整笔回滚并返回每批缺口。
     */
    @Transactional
    public InventoryViews.AllocationView allocate(AllocationRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        var order = request.orderId() == null ? null
                : reference.order(request.orderId()).orElse(null);
        if (request.orderId() == null) {
            errors.add(new ApiFieldError("orderId", "订单必填"));
        } else if (order == null) {
            errors.add(new ApiFieldError("orderId", "订单不存在"));
        } else if (!ORDER_CONFIRMED.equals(order.status())) {
            throw new ApiException(ErrorCode.STATE_NOT_EDITABLE, "仅已确认订单可领用库存",
                    List.of(new ApiFieldError("orderId", "草稿订单不能领用库存，请先确认订单")));
        }
        var lines = request.lines();
        if (lines == null || lines.isEmpty()) {
            errors.add(new ApiFieldError("lines", "至少一条领用明细"));
        } else {
            for (int index = 0; index < lines.size(); index++) {
                var line = lines.get(index);
                var prefix = "lines[" + index + "]";
                if (line.batchId() == null) {
                    errors.add(new ApiFieldError(prefix + ".batchId", "批次必填"));
                }
                if (line.orderItemId() == null) {
                    errors.add(new ApiFieldError(prefix + ".orderItemId", "订单明细必填"));
                } else if (order != null) {
                    var item = reference.orderItem(line.orderItemId()).orElse(null);
                    if (item == null || item.orderId() != order.orderId()) {
                        errors.add(new ApiFieldError(prefix + ".orderItemId", "明细不属于该订单"));
                    }
                }
                if (line.quantity() == null || line.quantity() < 1) {
                    errors.add(new ApiFieldError(prefix + ".quantity", "数量必须大于 0"));
                }
                if (line.targetNode() == null || !InventoryNodes.isNode(line.targetNode())) {
                    errors.add(new ApiFieldError(prefix + ".targetNode",
                            "接入工序必须是 MAKING/PACKING_BAG/SEAM_CUTTING/SHIPPABLE"));
                }
            }
        }
        failIfInvalid(errors);

        var batchIds = lines.stream().map(AllocationRequest.Line::batchId).distinct().sorted().toList();
        var locked = new HashMap<Long, InventoryBatchRow>();
        batchRepository.lockByIds(batchIds).forEach(batch -> locked.put(batch.id(), batch));
        var remaining = new HashMap<Long, Integer>();
        locked.forEach((id, batch) -> remaining.put(id, batch.quantity()));
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var prefix = "lines[" + index + "]";
            var batch = locked.get(line.batchId());
            if (batch == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "库存批次不存在",
                        List.of(new ApiFieldError(prefix + ".batchId", "批次不存在")));
            }
            if (!InventoryNodes.canAllocate(batch.node(), batch.seamState(), line.targetNode())) {
                throw new ApiException(ErrorCode.VALIDATION_INVALID, "批次与接入工序不兼容",
                        List.of(new ApiFieldError(prefix + ".targetNode",
                                "批次工序 " + batch.node() + "（缝边 " + batch.seamState() + "）不能接入 " + line.targetNode())));
            }
            int available = remaining.getOrDefault(batch.id(), 0);
            if (available < line.quantity()) {
                throw new ApiException(ErrorCode.STOCK_INSUFFICIENT, "库存不足",
                        List.of(new ApiFieldError(prefix + ".quantity",
                                "批次 " + batch.batchNo() + " 可用 " + available + "，需求 " + line.quantity())));
            }
            remaining.put(batch.id(), available - line.quantity());
        }

        var audit = auditContext.current();
        var allocation = new InventoryAllocationRow(null, order.orderId(), ALLOCATION_CONFIRMED,
                request.reason(), null, null, null, null, 0L);
        long allocationId = allocationRepository.insertAllocation(allocation, audit.requestId(),
                audit.idempotencyKey());
        var movement = new InventoryMovementRow(null,
                SequenceAllocator.format("IM", sequenceAllocator.next("inventory_movements"), 6),
                TYPE_ALLOCATION, java.time.LocalDate.now(), SOURCE_INVENTORY, allocationId, 0L, null,
                request.reason(), audit.adminUsername(), null);
        long movementId = movementRepository.insertMovement(movement, audit.requestId(),
                audit.idempotencyKey());
        var lineViews = new ArrayList<InventoryViews.AllocationLineView>();
        for (var line : lines) {
            var batch = locked.get(line.batchId());
            int before = batch.quantity();
            int after = before - line.quantity();
            long movementLineId = movementRepository.insertLine(new InventoryMovementLineRow(null, movementId,
                    batch.id(), DIRECTION_OUT, line.quantity(), before, after, batch.productId(),
                    batch.node(), batch.seamState(), line.orderItemId(), "订单领用"),
                    audit.requestId());
            batchRepository.updateQuantity(batch, after, audit.requestId());
            long entryId = fulfillmentLedger.registerInflow(order.orderId(), line.orderItemId(),
                    ENTRY_INVENTORY_ALLOCATION, line.targetNode(), line.quantity(), SOURCE_INVENTORY,
                    movementId, movementLineId, java.time.LocalDate.now(), audit.adminUsername(),
                    "库存领用接入", audit.requestId());
            fulfillmentLedger.applyInflow(line.orderItemId(), line.targetNode(), line.quantity(), true,
                    audit.requestId());
            long allocationLineId = allocationRepository.insertLine(new InventoryAllocationLineRow(null,
                    allocationId, batch.id(), line.orderItemId(), line.quantity(), line.targetNode(),
                    movementLineId, entryId, null), audit.requestId());
            var item = reference.orderItem(line.orderItemId()).orElse(null);
            lineViews.add(new InventoryViews.AllocationLineView(allocationLineId, batch.id(), batch.batchNo(),
                    line.orderItemId(), item == null ? null : item.lineNo(),
                    item == null ? null : item.orderNo(), line.quantity(), line.targetNode(), movementLineId,
                    before, after, entryId));
        }
        return new InventoryViews.AllocationView(allocationId, order.orderId(), order.orderNo(),
                ALLOCATION_CONFIRMED, request.reason(), null, null, 0L, lineViews);
    }

    public List<InventoryViews.AllocationView> listAllocations(Long orderId) {
        return allocationRepository.findByOrder(orderId).stream().map(this::toAllocationView).toList();
    }

    // ---------- 领用取消（任务 4.9） ----------

    /** 取消未被后续生产/核验/发货消费的领用：原子生成反向入库流水与反向履约事实，原事实不变。 */
    @Transactional
    public InventoryViews.AllocationView cancelAllocation(long allocationId, String reason) {
        var allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "领用不存在"));
        if (ALLOCATION_CANCELLED.equals(allocation.status())) {
            throw new ApiException(ErrorCode.STATE_CANNOT_CANCEL, "该领用已取消");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "取消原因必填",
                    List.of(new ApiFieldError("reason", "取消原因必填")));
        }
        var lines = allocationRepository.findLines(allocationId);
        for (var line : lines) {
            if (fulfillmentLedger.hasDownstreamConsumption(line.orderItemId(), line.fulfillmentEntryId())) {
                throw new ApiException(ErrorCode.STATE_CANNOT_CANCEL,
                        "领用数量已进入后续生产、核验或发货，请使用更正或售后流程",
                        List.of(new ApiFieldError("allocationId", "已被后续事实消费")));
            }
        }

        var audit = auditContext.current();
        var originalMovementId = movementRepository.findLineById(lines.get(0).movementLineId())
                .map(InventoryMovementLineRow::movementId).orElseThrow();
        var cancelMovement = new InventoryMovementRow(null,
                SequenceAllocator.format("IM", sequenceAllocator.next("inventory_movements"), 6),
                TYPE_ALLOCATION_CANCEL, java.time.LocalDate.now(), SOURCE_INVENTORY_CANCEL, allocationId, 0L,
                originalMovementId, reason, audit.adminUsername(), null);
        long cancelMovementId = movementRepository.insertMovement(cancelMovement, audit.requestId(),
                audit.idempotencyKey());
        for (var line : lines) {
            var batch = batchRepository.findById(line.batchId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "库存批次不存在"));
            int before = batch.quantity();
            int after = before + line.quantity();
            long movementLineId = movementRepository.insertLine(new InventoryMovementLineRow(null,
                    cancelMovementId, batch.id(), DIRECTION_IN, line.quantity(), before, after,
                    batch.productId(), batch.node(), batch.seamState(), line.orderItemId(), "领用取消回补"),
                    audit.requestId());
            batchRepository.updateQuantity(batch, after, audit.requestId());
            fulfillmentLedger.registerOutflow(allocation.orderId(), line.orderItemId(),
                    ENTRY_INVENTORY_ALLOCATION, line.targetNode(), line.quantity(), SOURCE_INVENTORY_CANCEL,
                    cancelMovementId, movementLineId, java.time.LocalDate.now(), audit.adminUsername(),
                    "领用取消反向", audit.requestId());
            fulfillmentLedger.applyInflow(line.orderItemId(), line.targetNode(), line.quantity(), false,
                    audit.requestId());
        }
        if (allocationRepository.markCancelled(allocationId, allocation.version(), audit.adminUsername(),
                reason, audit.requestId()) == 0) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
        return toAllocationView(allocationRepository.findById(allocationId).orElseThrow());
    }

    // ---------- 流水冲销（任务 4.10） ----------

    /**
     * 售后库存领用（任务 8.6）：从**成品批次（可发货）**扣库存，只增加售后可补发。
     * 售后补发不改变原订单履约，因此没有订单明细与接入工序；
     * 库存只扣一次（与订单领用同一套流水），冲销原流水时同步冲销售后台账事实。
     */
    @Transactional
    public InventoryViews.MovementView allocateToAfterSales(AfterSalesAllocationRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        if (request.afterSalesItemId() == null) {
            errors.add(new ApiFieldError("afterSalesItemId", "售后明细必填"));
        }
        if (request.batchId() == null) {
            errors.add(new ApiFieldError("batchId", "批次必填"));
        }
        if (request.quantity() == null || request.quantity() < 1) {
            errors.add(new ApiFieldError("quantity", "数量必须大于 0"));
        }
        failIfInvalid(errors);

        var item = reference.afterSalesItem(request.afterSalesItemId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "售后明细不存在"));
        var batch = batchRepository.lockByIds(List.of(request.batchId())).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "库存批次不存在",
                        List.of(new ApiFieldError("batchId", "批次不存在"))));
        if (!InventoryNodes.SHIPPABLE.equals(batch.node())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "售后补发只能领用成品批次（可发货）",
                    List.of(new ApiFieldError("batchId", "批次工序为 " + batch.node())));
        }
        if (batch.productId() != item.productId()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "批次商品与售后明细商品不一致",
                    List.of(new ApiFieldError("batchId", "批次商品 " + batch.productNo()
                            + "，售后明细商品 " + item.productNo())));
        }
        if (batch.quantity() < request.quantity()) {
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT, "库存不足",
                    List.of(new ApiFieldError("quantity", "批次 " + batch.batchNo() + " 可用 "
                            + batch.quantity() + "，需求 " + request.quantity())));
        }

        var audit = auditContext.current();
        var movement = new InventoryMovementRow(null,
                SequenceAllocator.format("IM", sequenceAllocator.next("inventory_movements"), 6),
                TYPE_AFTER_SALES_ALLOCATION, java.time.LocalDate.now(), SOURCE_AFTER_SALES,
                request.afterSalesItemId(), 0L, null, request.reason(), audit.adminUsername(), null);
        long movementId = movementRepository.insertMovement(movement, audit.requestId(),
                audit.idempotencyKey());
        int before = batch.quantity();
        int after = before - request.quantity();
        long lineId = movementRepository.insertLine(new InventoryMovementLineRow(null, movementId, batch.id(),
                DIRECTION_OUT, request.quantity(), before, after, batch.productId(), batch.node(),
                batch.seamState(), null, "售后补发领用"), audit.requestId());
        batchRepository.updateQuantity(batch, after, audit.requestId());
        afterSalesLedger.registerInflow(item.afterSalesItemId(), "INVENTORY_INFLOW", request.quantity(),
                com.yumi.orders.ledger.AfterSalesLedger.SOURCE_INVENTORY, movementId, lineId,
                java.time.LocalDate.now(), audit.adminUsername(), "售后库存领用接入", audit.requestId());
        return listMovements(null, null, null, null).stream()
                .filter(view -> view.id() == movementId).findFirst().orElseThrow();
    }

    /** 冲销未被后续事实消费的流水：新增反向流水并保留完整关联历史，原流水不可改删。 */
    @Transactional
    public InventoryViews.MovementView reverseMovement(long movementId, String reason) {
        var original = movementRepository.findById(movementId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "库存流水不存在"));
        if (TYPE_REVERSAL.equals(original.movementType())) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "冲销流水不可再次冲销");
        }
        if (movementRepository.findReversalOf(movementId).isPresent()) {
            throw new ApiException(ErrorCode.STATE_CANNOT_CANCEL, "该流水已被冲销");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "冲销原因必填",
                    List.of(new ApiFieldError("reason", "冲销原因必填")));
        }
        var lines = movementRepository.findLines(movementId, null, null);
        var audit = auditContext.current();
        var reversal = new InventoryMovementRow(null,
                SequenceAllocator.format("IM", sequenceAllocator.next("inventory_movements"), 6),
                TYPE_REVERSAL, java.time.LocalDate.now(), original.sourceType(), original.sourceId(),
                original.sourceLineId(), movementId, reason, audit.adminUsername(), null);
        long reversalId = movementRepository.insertMovement(reversal, audit.requestId(),
                audit.idempotencyKey());
        for (var line : lines) {
            var batch = batchRepository.findById(line.batchId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "库存批次不存在"));
            int before = batch.quantity();
            boolean reverseOutbound = DIRECTION_IN.equals(line.direction());
            if (reverseOutbound && before < line.quantity()) {
                throw new ApiException(ErrorCode.STATE_CANNOT_CANCEL,
                        "批次数量已被后续业务消费，不能直接冲销",
                        List.of(new ApiFieldError("movementId", "批次 " + batch.batchNo() + " 当前数量不足")));
            }
            int after = reverseOutbound ? before - line.quantity() : before + line.quantity();
            movementRepository.insertLine(new InventoryMovementLineRow(null, reversalId, batch.id(),
                    reverseOutbound ? DIRECTION_OUT : DIRECTION_IN, line.quantity(), before, after,
                    batch.productId(), batch.node(), batch.seamState(), line.orderItemId(), "冲销原流水"),
                    audit.requestId());
            batchRepository.updateQuantity(batch, after, audit.requestId());
        }
        if (SOURCE_AFTER_SALES.equals(original.sourceType())) {
            int total = lines.stream().mapToInt(InventoryMovementLineRow::quantity).sum();
            afterSalesLedger.registerReversal(original.sourceId(), total,
                    com.yumi.orders.ledger.AfterSalesLedger.SOURCE_INVENTORY, reversalId, 0L,
                    java.time.LocalDate.now(), audit.adminUsername(), "售后库存领用冲销：" + reason,
                    audit.requestId());
        }
        return listMovements(null, null, null, null).stream()
                .filter(view -> view.id() == reversalId).findFirst().orElseThrow();
    }

    // ---------- 内部工具 ----------

    private InventoryViews.AllocationView toAllocationView(InventoryAllocationRow row) {
        var order = reference.order(row.orderId()).orElse(null);
        return new InventoryViews.AllocationView(row.id(), row.orderId(),
                order == null ? null : order.orderNo(), row.status(), row.reason(), row.cancelledBy(),
                row.cancelReason(), row.version(),
                allocationRepository.findLines(row.id()).stream().map(line -> {
                    var batch = batchRepository.findById(line.batchId()).orElse(null);
                    var movementLine = movementRepository.findLineById(line.movementLineId()).orElse(null);
                    var item = reference.orderItem(line.orderItemId()).orElse(null);
                    return new InventoryViews.AllocationLineView(line.id(), line.batchId(),
                            batch == null ? null : batch.batchNo(), line.orderItemId(),
                            item == null ? null : item.lineNo(), item == null ? null : item.orderNo(),
                            line.quantity(), line.targetNode(), line.movementLineId(),
                            movementLine == null ? 0 : movementLine.quantityBefore(),
                            movementLine == null ? 0 : movementLine.quantityAfter(),
                            line.fulfillmentEntryId());
                }).toList());
    }

    // ---------- 查询（任务 4.2） ----------

    public List<InventoryViews.BatchView> listBatches(Long productId, String node, String seamState,
                                                     boolean includeEmpty) {
        return batchRepository.find(productId, node, seamState, includeEmpty).stream()
                .map(InventoryService::toBatchView)
                .toList();
    }

    public List<InventoryViews.SummaryView> summary(Long productId, boolean includeEmpty) {
        return batchRepository.summarize(productId, includeEmpty).stream()
                .map(row -> new InventoryViews.SummaryView(
                        ((Number) row.get("product_id")).longValue(),
                        String.valueOf(row.get("product_no")),
                        String.valueOf(row.get("product_name")),
                        String.valueOf(row.get("node")),
                        String.valueOf(row.get("seam_state")),
                        ((Number) row.get("quantity")).intValue(),
                        ((Number) row.get("batch_count")).intValue()))
                .toList();
    }

    public List<InventoryViews.MovementView> listMovements(String movementType, Long batchId,
                                                          LocalDate from, LocalDate to) {
        return movementRepository.find(movementType, batchId, from, to).stream()
                .map(movement -> new InventoryViews.MovementView(movement.id(), movement.movementNo(),
                        movement.movementType(), movement.businessDate(), movement.sourceType(),
                        movement.reversesMovementId(), movement.reason(), movement.operatorUsername(),
                        movement.note(),
                        movementRepository.findLines(movement.id(), null, null).stream()
                                .map(this::toLineView)
                                .toList()))
                .toList();
    }

    /** 领用推荐（任务 4.5）：按商品 + 接入工序 + 缝边兼容性筛出可接入批次，FIFO 排序，只推荐不占用。 */
    public List<InventoryViews.RecommendationView> recommendations(long productId, String targetNode) {
        if (!InventoryNodes.isNode(targetNode)) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID, "接入工序不合法",
                    List.of(new ApiFieldError("targetNode", "接入工序必须是 MAKING/PACKING_BAG/SEAM_CUTTING/SHIPPABLE")));
        }
        return batchRepository.find(productId, null, null, false).stream()
                .filter(batch -> InventoryNodes.canAllocate(batch.node(), batch.seamState(), targetNode))
                .map(batch -> new InventoryViews.RecommendationView(batch.id(), batch.batchNo(), batch.productId(),
                        batch.productNo(), batch.productName(), batch.node(), batch.seamState(),
                        batch.quantity(), batch.inventoryDate(),
                        InventoryNodes.allowedTargets(batch.node(), batch.seamState())))
                .toList();
    }

    // ---------- 期初库存（任务 4.3） ----------

    @Transactional
    public InventoryViews.BatchView opening(OpeningRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        var product = requireProduct(request.productId(), errors);
        requireNode(request.node(), errors);
        var seamState = request.seamState() == null ? InventoryNodes.SEAM_NONE : request.seamState();
        if (!InventoryNodes.isSeamState(seamState)) {
            errors.add(new ApiFieldError("seamState", "缝边状态必须是 NONE 或 DONE"));
        } else if (!InventoryNodes.isValidCombination(request.node(), seamState)) {
            errors.add(new ApiFieldError("seamState",
                    "工序与缝边状态组合不合法：制作/捏毛装袋为未缝边，缝边剪袋为已缝边，可发货两种均可"));
        }
        if (request.quantity() == null || request.quantity() < 1) {
            errors.add(new ApiFieldError("quantity", "数量必须大于 0"));
        }
        if (request.inventoryDate() == null) {
            errors.add(new ApiFieldError("inventoryDate", "盘点日期必填"));
        }
        failIfInvalid(errors);

        var audit = auditContext.current();
        var movement = new InventoryMovementRow(null,
                SequenceAllocator.format("IM", sequenceAllocator.next("inventory_movements"), 6),
                TYPE_OPENING, request.inventoryDate(), SOURCE_OPENING, 0L, 0L, null,
                "期初库存", audit.adminUsername(), request.note());
        long movementId = movementRepository.insertMovement(movement, audit.requestId(), audit.idempotencyKey());
        var batch = new InventoryBatchRow(null,
                SequenceAllocator.format("IB", sequenceAllocator.next("inventory_batches"), 6),
                product.id(), product.productNo(), product.name(), SOURCE_OPENING, movementId, 0L,
                request.node(), seamState, request.quantity(), request.inventoryDate(), request.note(), 0L);
        long batchId = batchRepository.insert(batch, audit.requestId(), audit.idempotencyKey());
        movementRepository.insertLine(new InventoryMovementLineRow(null, movementId, batchId, DIRECTION_IN,
                request.quantity(), 0, request.quantity(), product.id(), request.node(), seamState, null,
                "期初入库"), audit.requestId());
        return toBatchView(batchRepository.findById(batchId).orElseThrow());
    }

    // ---------- 盘点调整（任务 4.4） ----------

    /** 提交实际数量：按差异生成盘盈/盘亏流水；数量一致不写任何记录，返回当前批次。 */
    @Transactional
    public InventoryViews.BatchView adjust(AdjustmentRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        if (request.batchId() == null) {
            errors.add(new ApiFieldError("batchId", "批次必填"));
        }
        if (request.actualQuantity() == null || request.actualQuantity() < 0) {
            errors.add(new ApiFieldError("actualQuantity", "实际数量不能为负数"));
        }
        if (request.reason() == null || request.reason().isBlank()) {
            errors.add(new ApiFieldError("reason", "盘点原因必填"));
        }
        failIfInvalid(errors);

        var batch = batchRepository.findById(request.batchId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "库存批次不存在"));
        int diff = request.actualQuantity() - batch.quantity();
        if (diff == 0) {
            return toBatchView(batch);
        }
        var audit = auditContext.current();
        var movement = new InventoryMovementRow(null,
                SequenceAllocator.format("IM", sequenceAllocator.next("inventory_movements"), 6),
                TYPE_ADJUSTMENT, LocalDate.now(), SOURCE_ADJUSTMENT, batch.id(), 0L, null,
                request.reason(), audit.adminUsername(), request.note());
        long movementId = movementRepository.insertMovement(movement, audit.requestId(), audit.idempotencyKey());
        String direction = diff > 0 ? DIRECTION_IN : DIRECTION_OUT;
        int quantity = Math.abs(diff);
        int after = request.actualQuantity();
        movementRepository.insertLine(new InventoryMovementLineRow(null, movementId, batch.id(), direction,
                quantity, batch.quantity(), after, batch.productId(), batch.node(), batch.seamState(), null,
                diff > 0 ? "盘盈" : "盘亏"), audit.requestId());
        batchRepository.updateQuantity(batch, after, audit.requestId());
        return toBatchView(batchRepository.findById(batch.id()).orElseThrow());
    }

    // ---------- 内部工具 ----------

    private InventoryReference.Product requireProduct(Long productId, List<ApiFieldError> errors) {
        if (productId == null) {
            errors.add(new ApiFieldError("productId", "商品必填"));
            return null;
        }
        var product = reference.product(productId).orElse(null);
        if (product == null) {
            errors.add(new ApiFieldError("productId", "商品不存在"));
        } else if (!PRODUCT_ACTIVE.equals(product.status())) {
            errors.add(new ApiFieldError("productId", "商品已停用"));
        }
        return product;
    }

    private static void requireNode(String node, List<ApiFieldError> errors) {
        if (node == null || !InventoryNodes.isNode(node)) {
            errors.add(new ApiFieldError("node", "已完成工序必须是 MAKING/PACKING_BAG/SEAM_CUTTING/SHIPPABLE"));
        }
    }

    private InventoryViews.MovementLineView toLineView(InventoryMovementLineRow line) {
        var batch = batchRepository.findById(line.batchId()).orElse(null);
        var orderItem = line.orderItemId() == null ? null
                : reference.orderItem(line.orderItemId()).orElse(null);
        return new InventoryViews.MovementLineView(line.id(), line.batchId(),
                batch == null ? null : batch.batchNo(), line.direction(), line.quantity(),
                line.quantityBefore(), line.quantityAfter(), line.productId(),
                batch == null ? null : batch.productNo(), batch == null ? null : batch.productName(),
                line.node(), line.seamState(), line.orderItemId(),
                orderItem == null ? null : orderItem.lineNo(), orderItem == null ? null : orderItem.orderNo(),
                line.note());
    }

    private static InventoryViews.BatchView toBatchView(InventoryBatchRow row) {
        return new InventoryViews.BatchView(row.id(), row.batchNo(), row.productId(), row.productNo(),
                row.productName(), row.sourceType(), row.node(), row.seamState(), row.quantity(),
                row.inventoryDate(), row.note(), row.version());
    }

    private static void failIfInvalid(List<ApiFieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_INVALID,
                    ErrorCode.VALIDATION_INVALID.defaultMessage(), errors);
        }
    }
}
