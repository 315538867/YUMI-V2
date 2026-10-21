package com.yumi.orders.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.orders.api.ChangeConfirmedOrderCommand;
import com.yumi.orders.api.OrderChangeAction;
import com.yumi.orders.api.OrderChangeKind;
import com.yumi.orders.api.OrderChangeRef;
import com.yumi.orders.api.OrderChangeService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.orders.application.port.OrderRepository;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.ProductCostBasisQuery;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.production.api.DemandReductionPreview;
import com.yumi.production.api.ProductionBasis;
import com.yumi.production.api.ProductionDemandParticipant;
import com.yumi.production.api.ReduceProductionDemandCommand;
import com.yumi.production.api.ShrinkUnverifiedCommand;
import com.yumi.production.api.ProductionTaskAdjustmentParticipant;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单变更实现（基线 12 §7.2）。
 *
 * <p>分类处理：价格/收件人只追加约定版本；数量与取消同时下调需求并释放未核验预留；
 * 生产定义变化建立继任订单项、继任约定组与新需求，原需求等额减少，旧事实全部保留。
 */
public class OrderChangeServiceImpl implements OrderChangeService {

    private final OrderRepository orders;
    private final ProductionDemandParticipant productionDemands;
    private final ProductionTaskAdjustmentParticipant taskAdjustments;
    private final ProductSnapshotService productSnapshots;
    private final ProductCostBasisQuery costBasis;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OrderChangeServiceImpl(
            OrderRepository orders,
            ProductionDemandParticipant productionDemands,
            ProductionTaskAdjustmentParticipant taskAdjustments,
            ProductSnapshotService productSnapshots,
            ProductCostBasisQuery costBasis,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.productionDemands = productionDemands;
        this.taskAdjustments = taskAdjustments;
        this.productSnapshots = productSnapshots;
        this.costBasis = costBasis;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public OrderChangeRef change(ChangeConfirmedOrderCommand command) {
        authorization.require(PermissionCodes.ORDER_CHANGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_CHANGE", "变更命令不能为空");
        }
        return transactionTemplate.execute(status -> doChange(command));
    }

    private OrderChangeRef doChange(ChangeConfirmedOrderCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        var order = orders.findOrder(command.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"));
        if (order.contentVersion() != command.expectedContentVersion()) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "ORDER_VERSION_CONFLICT",
                    "订单已被其他操作修改，请刷新后重试");
        }
        if (!"CONFIRMED".equals(order.status()) && !"IN_FULFILLMENT".equals(order.status())) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_NOT_CHANGEABLE",
                    "只有已确认或履约中的订单可以变更");
        }

        String changeId = "OCH-" + UUID.randomUUID();
        BigDecimal amountDelta = BigDecimal.ZERO;
        String changeType = command.actions().getFirst().kind().name();
        List<String> actions = new ArrayList<>();

        for (OrderChangeAction action : command.actions()) {
            var item = orders.findItem(action.orderItemBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "ORDER_ITEM_NOT_FOUND", "订单项不存在"));
            var group = orders.findGroup(item.orderItemBusinessId())
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "AGREEMENT_GROUP_NOT_FOUND", "交付约定组不存在"));
            var version = orders.findActiveVersion(
                            group.agreementGroupBusinessId(), group.activeVersionNo())
                    .orElseThrow();
            String demandId = demandIdOf(item.orderItemBusinessId());

            switch (action.kind()) {
                case PRICE_OR_RECIPIENT -> {
                    BigDecimal delta = priceOrRecipientChange(
                            changeId, action, item, group, version, demandId, now);
                    amountDelta = amountDelta.add(delta);
                    actions.add("VERSION_BUMP");
                }
                case QUANTITY -> {
                    int target = action.newQuantity() == null ? 0 : action.newQuantity();
                    BigDecimal delta = quantityChange(
                            changeId, action, item, group, version, demandId, target, now);
                    amountDelta = amountDelta.add(delta);
                    actions.add("QUANTITY_REDUCE");
                }
                case CANCELLATION -> {
                    BigDecimal delta = quantityChange(
                            changeId, action, item, group, version, demandId, 0, now);
                    amountDelta = amountDelta.add(delta);
                    actions.add("CANCELLATION");
                }
                case PRODUCTION_DEFINITION -> {
                    BigDecimal delta = productionDefinitionChange(
                            changeId, action, item, group, version, demandId, now, operatorId);
                    amountDelta = amountDelta.add(delta);
                    actions.add("SUCCESSOR");
                }
            }
        }

        orders.insertChangeFact(new OrderRepository.ChangeRecord(
                changeId, command.orderBusinessId(), changeType, command.reasonText(), amountDelta,
                now, operatorId));
        BigDecimal receivable = order.currentReceivableAmount().add(amountDelta);
        if (receivable.signum() < 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_RECEIVABLE_NEGATIVE",
                    "变更后应收金额为负，拒绝提交");
        }
        var projection = orders.findProjection(command.orderBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "ORDER_PROJECTION_MISSING", "订单履约投影缺失"));
        int requiredQuantity = updatedRequiredQuantity(command.orderBusinessId());
        boolean deliveryFulfilled = projection.shippedQuantity() >= requiredQuantity;
        BigDecimal netReceived = projection.receivedAmount().subtract(projection.refundedAmount());
        boolean paymentFulfilled = netReceived.compareTo(receivable) >= 0;

        orders.updateOrderReceivable(
                command.orderBusinessId(), receivable, command.expectedContentVersion());
        reconcileRefundObligation(command.orderBusinessId(), netReceived, receivable, now);
        orders.insertFulfillmentProjection(new OrderRepository.ProjectionRecord(
                command.orderBusinessId(), requiredQuantity, projection.shippedQuantity(),
                receivable, projection.receivedAmount(), projection.refundedAmount(),
                deliveryFulfilled, paymentFulfilled,
                blockingReasons(deliveryFulfilled, paymentFulfilled), 1L));
        orders.updateOrderDeliveryFulfilled(
                command.orderBusinessId(), deliveryFulfilled, paymentFulfilled,
                command.expectedContentVersion() + 1);
        var updated = orders.findOrder(command.orderBusinessId()).orElseThrow();

        return new OrderChangeRef(
                changeId, command.orderBusinessId(), updated.contentVersion(), actions.size(),
                SettlementMoney.of(receivable, CurrencyCode.CNY));
    }

    /**
     * 订单减价后已收超过应收的差额形成退款义务；后续加价则缩减或关闭。
     * 已退金额不能被本轮缩减吞掉，因此 decided 不低于 paid。
     */
    private void reconcileRefundObligation(
            String orderBusinessId, BigDecimal netReceived, BigDecimal receivable, Instant now) {
        BigDecimal overpayment = netReceived.subtract(receivable).max(BigDecimal.ZERO);
        var existing = orders.findRefundObligation(orderBusinessId);
        if (overpayment.signum() == 0) {
            existing.ifPresent(row -> orders.updateRefundObligation(
                    row.refundBusinessId(), row.paidAmount(), row.paidAmount(),
                    row.paidAmount().signum() == 0 ? "CLOSED" : "PAID"));
            return;
        }
        BigDecimal paid = existing.map(OrderRepository.RefundObligationRecord::paidAmount)
                .orElse(BigDecimal.ZERO);
        BigDecimal decided = overpayment.max(paid);
        String status = paid.compareTo(decided) >= 0 ? "PAID" : "ACTIVE";
        if (existing.isPresent()) {
            orders.updateRefundObligation(
                    existing.get().refundBusinessId(), decided, paid, status);
        } else {
            orders.insertRefundObligation(new OrderRepository.RefundObligationRecord(
                    "RFO-" + UUID.randomUUID(), orderBusinessId, decided, BigDecimal.ZERO,
                    "ACTIVE", 1L));
        }
    }

    private static String blockingReasons(boolean deliveryFulfilled, boolean paymentFulfilled) {
        String reasons = "";
        if (!deliveryFulfilled) {
            reasons = "DELIVERY_PENDING";
        }
        if (!paymentFulfilled) {
            reasons = reasons.isBlank() ? "PAYMENT_PENDING" : reasons + ",PAYMENT_PENDING";
        }
        return reasons.isBlank() ? null : reasons;
    }

    private BigDecimal priceOrRecipientChange(
            String changeId, OrderChangeAction action, OrderRepository.ItemRecord item,
            OrderRepository.AgreementGroupRecord group, OrderRepository.AgreementVersionRecord version,
            String demandId, Instant now) {
        int nextVersionNo = group.activeVersionNo() + 1;
        BigDecimal unitPrice = action.newUnitPrice() == null
                ? version.unitPrice() : action.newUnitPrice().value();
        RecipientInfo recipient = action.newRecipient() == null
                ? readRecipient(version.recipientPayloadJson()) : action.newRecipient();
        orders.insertAgreementVersion(new OrderRepository.AgreementVersionRecord(
                "AGV-" + UUID.randomUUID(), group.agreementGroupBusinessId(), nextVersionNo,
                version.schemeSnapshotRef(), version.targetProcess(), unitPrice, version.quantity(),
                version.glueSource(), version.colorPasteSource(), version.bagSource(),
                version.decorationSource(), version.productionBasisJson(),
                version.productionBasisFingerprint(), writeJson(recipient),
                version.expectedCostPayloadJson(), "AGV-" + UUID.randomUUID(),
                sha256(version.contentFingerprint() + "|" + nextVersionNo), now));
        orders.updateGroupActiveQuantity(
                group.agreementGroupBusinessId(), group.activeQuantity(), nextVersionNo, group.status());
        orders.insertChangeAction(new OrderRepository.ChangeActionRecord(
                changeId, item.orderItemBusinessId(), group.agreementGroupBusinessId(), null, null,
                demandId, null, "VERSION_BUMP", 0, group.activeQuantity(), group.activeQuantity(),
                group.activeVersionNo(), nextVersionNo));
        BigDecimal delta = unitPrice.subtract(version.unitPrice())
                .multiply(BigDecimal.valueOf(version.quantity()));
        return delta;
    }

    private BigDecimal quantityChange(
            String changeId, OrderChangeAction action, OrderRepository.ItemRecord item,
            OrderRepository.AgreementGroupRecord group, OrderRepository.AgreementVersionRecord version,
            String demandId, int target, Instant now) {
        int before = group.activeQuantity();
        int reduction = before - target;
        if (reduction < 0) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_CHANGE_UNSUPPORTED",
                    "一期不支持通过变更增加订单数量，请新建订单");
        }
        if (reduction > 0) {
            reduceDemand(demandId, target, group.agreementGroupBusinessId());
            // 释放该来源尚未核验的任务预留（混合来源任务只动目标来源）
            taskAdjustments.shrinkUnverified(new ShrinkUnverifiedCommand(
                    productionSourceOf(demandId), reduction, "ORDER_REDUCTION", action.kind().name()));
        }
        int nextVersionNo = group.activeVersionNo() + 1;
        orders.insertAgreementVersion(new OrderRepository.AgreementVersionRecord(
                "AGV-" + UUID.randomUUID(), group.agreementGroupBusinessId(), nextVersionNo,
                version.schemeSnapshotRef(), version.targetProcess(), version.unitPrice(), target,
                version.glueSource(), version.colorPasteSource(), version.bagSource(),
                version.decorationSource(), version.productionBasisJson(),
                version.productionBasisFingerprint(), version.recipientPayloadJson(),
                version.expectedCostPayloadJson(), "AGV-" + UUID.randomUUID(),
                sha256(version.contentFingerprint() + "|" + nextVersionNo + "|" + target), now));
        String status = target == 0 ? "CANCELLED" : group.status();
        orders.updateGroupActiveQuantity(
                group.agreementGroupBusinessId(), target, nextVersionNo, status);
        orders.updateItemActiveQuantity(
                item.orderItemBusinessId(), target, target == 0 ? "CANCELLED" : item.status());
        orders.insertChangeAction(new OrderRepository.ChangeActionRecord(
                changeId, item.orderItemBusinessId(), group.agreementGroupBusinessId(), null, null,
                demandId, null, reduction > 0 ? "QUANTITY_REDUCE" : "CANCELLATION", 0, before, target,
                group.activeVersionNo(), nextVersionNo));
        return version.unitPrice().multiply(BigDecimal.valueOf(target - before));
    }

    private BigDecimal productionDefinitionChange(
            String changeId, OrderChangeAction action, OrderRepository.ItemRecord item,
            OrderRepository.AgreementGroupRecord group, OrderRepository.AgreementVersionRecord version,
            String demandId, Instant now, String operatorId) {
        int migrated = action.migratedQuantity();
        if (migrated > group.activeQuantity()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_CHANGE_UNSUPPORTED",
                    "迁移数量不能超过当前有效数量");
        }
        var schemeSnapshot = productSnapshots.resolveScheme(action.newSchemeSnapshotRef());
        var basis = costBasis.resolveProductionBasis(
                action.newSchemeSnapshotRef(), action.newTargetProcess(),
                new ProductCostBasisQuery.MaterialSourceAgreement(
                        action.newMaterialSources().glue(), action.newMaterialSources().colorPaste(),
                        action.newMaterialSources().bag(), action.newMaterialSources().decoration()));

        // 继任订单项 + 继任约定组 + 新约定版本；原项与原需求原样保留并等额减少
        String successorItemId = "OIT-" + UUID.randomUUID();
        String successorGroupId = "AGR-" + UUID.randomUUID();
        String successorVersionId = "AGV-" + UUID.randomUUID();
        String successorDemandSource = "ORDER_ITEM:" + successorItemId;
        orders.insertItem(new OrderRepository.ItemRecord(
                successorItemId, item.orderBusinessId(), nextItemNo(item.orderBusinessId()),
                item.productBusinessId(), schemeSnapshot.productSnapshotRef().snapshotBusinessId(),
                migrated, migrated, "ACTIVE", 1L));
        orders.insertAgreementGroup(new OrderRepository.AgreementGroupRecord(
                successorGroupId, successorItemId, 1, migrated, 0, "ACTIVE", 1L));
        orders.insertAgreementVersion(new OrderRepository.AgreementVersionRecord(
                successorVersionId, successorGroupId, 1,
                action.newSchemeSnapshotRef().snapshotBusinessId(), action.newTargetProcess().name(),
                version.unitPrice(), migrated, action.newMaterialSources().glue().name(),
                action.newMaterialSources().colorPaste().name(), action.newMaterialSources().bag().name(),
                action.newMaterialSources().decoration().name(),
                writeJson(Map.of("fingerprint", basis.contentFingerprint().value())),
                basis.contentFingerprint().value(), version.recipientPayloadJson(), null,
                successorVersionId, sha256(successorVersionId + basis.contentFingerprint().value()), now));

        reduceDemand(demandId, group.activeQuantity() - migrated, group.agreementGroupBusinessId());
        taskAdjustments.shrinkUnverified(new ShrinkUnverifiedCommand(
                productionSourceOf(demandId), migrated, "ORDER_CHANGE", "PRODUCTION_DEFINITION"));

        ProductionBasis productionBasis = toProductionBasis(basis);
        var prepared = productionDemands.prepareCreate(new com.yumi.production.api.CreateProductionDemandRequest(
                "orders", "ORDER_ITEM", successorItemId, item.orderBusinessId(), successorItemId,
                com.yumi.production.api.DemandType.NEW_PRODUCT, item.productBusinessId(),
                schemeSnapshot.productSnapshotRef().snapshotBusinessId(), successorDemandSource,
                action.newSchemeSnapshotRef().snapshotBusinessId(), productionBasis, migrated, null));
        var successorDemand = productionDemands.commitCreate(prepared);

        int remaining = group.activeQuantity() - migrated;
        orders.updateGroupActiveQuantity(
                group.agreementGroupBusinessId(), remaining, group.activeVersionNo(),
                remaining == 0 ? "CANCELLED" : group.status());
        orders.updateItemActiveQuantity(
                item.orderItemBusinessId(), remaining, remaining == 0 ? "SUPERSEDED" : item.status());
        orders.insertChangeAction(new OrderRepository.ChangeActionRecord(
                changeId, item.orderItemBusinessId(), group.agreementGroupBusinessId(), successorItemId,
                successorGroupId, demandId, successorDemand.demandBusinessId(), "SUCCESSOR", migrated,
                group.activeQuantity(), remaining, group.activeVersionNo(), group.activeVersionNo()));
        return BigDecimal.ZERO;
    }

    private void reduceDemand(String demandId, int target, String groupId) {
        DemandReductionPreview preview = productionDemands.previewReduction(
                new ReduceProductionDemandCommand(
                        demandId, target, List.of(), "ORDER_CHANGE", "订单变更"));
        if (!preview.isAllowed()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "ORDER_CHANGE_BLOCKED",
                    "订单变更被生产端拒绝：" + String.join("；", preview.blockingReasons()));
        }
        productionDemands.commitReduction(new ReduceProductionDemandCommand(
                demandId, target, List.of(), "ORDER_CHANGE", "订单变更"));
    }

    private String demandIdOf(String orderItemBusinessId) {
        return orders.findProductionDemandIdOfItem(orderItemBusinessId);
    }

    private String productionSourceOf(String demandId) {
        return orders.findProductionSourceIdOfDemand(demandId);
    }

    private int nextItemNo(String orderBusinessId) {
        return orders.findItems(orderBusinessId).size() + 1;
    }

    private int updatedRequiredQuantity(String orderBusinessId) {
        return orders.findItems(orderBusinessId).stream()
                .filter(item -> !"CANCELLED".equals(item.status()))
                .mapToInt(OrderRepository.ItemRecord::activeQuantity)
                .sum();
    }

    private RecipientInfo readRecipient(String json) {
        try {
            var node = objectMapper.readTree(json);
            return new RecipientInfo(
                    node.path("customerName").asText(),
                    node.path("phone").asText(),
                    node.path("address").asText());
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "ORDER_PAYLOAD_INVALID", "收货信息无法解析");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "ORDER_PAYLOAD_INVALID", "订单约定内容无法序列化");
        }
    }

    private static ProductionBasis toProductionBasis(ProductCostBasisQuery.ResolvedProductionBasis basis) {
        return new ProductionBasis(
                basis.schemeSnapshotRef().snapshotBusinessId(),
                basis.productSnapshotRef().snapshotBusinessId(), basis.schemeBusinessId(),
                com.yumi.production.api.TargetProcess.valueOf(basis.targetProcess().name()),
                new ProductionBasis.MaterialSources(
                        basis.materialSources().glue(), basis.materialSources().colorPaste(),
                        basis.materialSources().bag(), basis.materialSources().decoration()),
                basis.costLines().stream().map(line -> new ProductionBasis.CostLine(
                        line.componentCode(),
                        line.processCode() == null ? null
                                : com.yumi.production.api.TargetProcess.valueOf(line.processCode()),
                        line.nature(), line.source(), line.quantity(), line.unitPrice(),
                        line.fullResourceCost())).toList(),
                basis.formulaRuleVersion(), basis.roundingVersion(), basis.contentFingerprint(),
                basis.makingStandardMinutes(), basis.baggingStandardMinutes(),
                basis.seamStandardMinutes());
    }

    private static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
