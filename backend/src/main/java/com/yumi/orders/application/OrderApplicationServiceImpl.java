package com.yumi.orders.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.customer.api.CustomerSnapshotRef;
import com.yumi.customer.api.CustomerSnapshotService;
import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.OrderApplicationService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderOrigin;
import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderStatus;
import com.yumi.orders.api.OrderSummaryView;
import com.yumi.orders.api.OrderView;
import com.yumi.orders.application.port.OrderRepository;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.idempotency.IdempotentCommandService;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.product.api.ProductCostBasisQuery;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.product.api.SchemeView;
import com.yumi.production.api.CreateProductionDemandRequest;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.PreparedDemand;
import com.yumi.production.api.ProductionBasis;
import com.yumi.production.api.ProductionDemandParticipant;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 订单确认实现（基线 12 §7.1、§10）。
 *
 * <p>与生产需求的建立同事务：任一参与端失败整笔回滚，不留半条订单；幂等键命中时直接复用原订单。
 */
public class OrderApplicationServiceImpl implements OrderApplicationService, OrderQuery {

    private static final String SCOPE = "orders.trade.confirm";
    private static final String RESULT_TYPE = "ORDER";

    private final OrderRepository orders;
    private final CustomerSnapshotService customerSnapshots;
    private final ProductSnapshotService productSnapshots;
    private final ProductCostBasisQuery costBasis;
    private final ProductionDemandParticipant productionDemands;
    private final IdempotentCommandService idempotency;
    private final BusinessNumberService numbering;
    private final AuthorizationService authorization;
    private final CurrentActorProvider currentActor;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public OrderApplicationServiceImpl(
            OrderRepository orders,
            CustomerSnapshotService customerSnapshots,
            ProductSnapshotService productSnapshots,
            ProductCostBasisQuery costBasis,
            ProductionDemandParticipant productionDemands,
            IdempotentCommandService idempotency,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.customerSnapshots = customerSnapshots;
        this.productSnapshots = productSnapshots;
        this.costBasis = costBasis;
        this.productionDemands = productionDemands;
        this.idempotency = idempotency;
        this.numbering = numbering;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public OrderRef createAndConfirm(CreateConfirmedOrderCommand command) {
        authorization.require(PermissionCodes.ORDER_CREATE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_ORDER", "订单命令不能为空");
        }
        String idempotencyKey = command.idempotencyKey();
        String fingerprint = fingerprint(command);

        // 幂等登记与业务写入必须在同一事务内：命令体在事务中执行，生产需求等参与端都是 MANDATORY
        CommandOutcome outcome = transactionTemplate.execute(status -> idempotency.execute(
                new CommandRequest(SCOPE, idempotencyKey, fingerprint),
                () -> new CommandOutcome(RESULT_TYPE, doConfirm(command))).outcome());
        return requireRef(outcome.resultBusinessId());
    }

    @Override
    public OrderView get(String orderBusinessId) {
        authorization.require(PermissionCodes.ORDER_READ, ResourceScope.GLOBAL);
        return toView(requireOrder(orderBusinessId));
    }

    @Override
    public OrderView.FulfillmentView getFulfillment(String orderBusinessId) {
        authorization.require(PermissionCodes.ORDER_READ, ResourceScope.GLOBAL);
        return toView(requireOrder(orderBusinessId)).fulfillment();
    }

    @Override
    public List<OrderSummaryView> search(
            String keyword, OrderStatus status, int page, int size) {
        authorization.require(PermissionCodes.ORDER_READ, ResourceScope.GLOBAL);
        if (page < 0 || size <= 0 || size > 200) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "ORDER_PAGE_INVALID", "分页参数不合法");
        }
        return orders.searchOrders(
                        keyword == null ? "" : keyword.strip(),
                        status == null ? null : status.name(), page * size, size)
                .stream()
                .map(record -> new OrderSummaryView(
                        record.orderBusinessId(), record.orderNo(),
                        OrderOrigin.valueOf(record.origin()), OrderStatus.valueOf(record.status()),
                        record.customerBusinessId(), record.orderDate(),
                        record.expectedDeliveryDate(),
                        SettlementMoney.of(record.currentReceivableAmount(), CurrencyCode.CNY),
                        record.deliveryFulfilled(), record.paymentFulfilled(),
                        record.contentVersion(), record.updatedAt()))
                .toList();
    }

    private String doConfirm(CreateConfirmedOrderCommand command) {
        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();

        CustomerSnapshotRef customerSnapshot = customerSnapshots.acquireCurrentCustomerSnapshot(
                command.customerBusinessId(), command.contactOverride());

        String orderBusinessId = "ORD-" + UUID.randomUUID();
        String orderNo = numbering.nextNumber("SALES_ORDER", "SO", 6);

        // 先算出全部订单项约定与生产依据，再一并写入：任何一项失败都不留下半条订单
        List<PreparedItem> preparedItems = new ArrayList<>();
        int itemNo = 1;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalQuantity = 0;
        for (OrderItemRequest item : command.items()) {
            PreparedItem prepared = prepareItem(
                    orderBusinessId, itemNo++, item, command, now, operatorId);
            preparedItems.add(prepared);
            totalAmount = totalAmount.add(prepared.amount().value());
            totalQuantity += item.quantity();
        }

        SettlementMoney receivable = SettlementMoney.of(totalAmount, CurrencyCode.CNY);
        orders.insertOrder(new OrderRepository.OrderRecord(
                orderBusinessId, orderNo, OrderOrigin.NEW_ORDER.name(), command.customerBusinessId(),
                customerSnapshot.snapshotBusinessId(), command.orderDate(),
                command.expectedDeliveryDate(), OrderStatus.CONFIRMED.name(), receivable.value(),
                "CNY", false, false, 1L, 1L, now, operatorId, now));
        orders.insertConfirmationFact(new OrderRepository.ConfirmationRecord(
                "OCF-" + UUID.randomUUID(), orderBusinessId, "ORIGINAL", totalQuantity,
                totalAmount, now, operatorId));
        orders.insertReceivableFact(new OrderRepository.ReceivableRecord(
                "ORF-" + UUID.randomUUID(), orderBusinessId, "ORIGINAL", totalAmount, "CNY", null, now));
        orders.insertFulfillmentProjection(new OrderRepository.ProjectionRecord(
                orderBusinessId, totalQuantity, 0, totalAmount, BigDecimal.ZERO, BigDecimal.ZERO,
                false, false, "DELIVERY_PENDING,PAYMENT_PENDING", 1L));

        for (PreparedItem prepared : preparedItems) {
            writeItem(orderBusinessId, prepared, now);
            // 同事务建立生产需求，携带同一生效依据与指纹
            PreparedDemand demand = productionDemands.prepareCreate(prepared.demandRequest());
            productionDemands.commitCreate(demand);
        }

        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(), operatorId, "orders", "CONFIRM_ORDER", "ORDER",
                orderBusinessId, "SUCCESS", Map.of("orderNo", orderNo), now));
        return orderBusinessId;
    }

    private PreparedItem prepareItem(
            String orderBusinessId, int itemNo, OrderItemRequest item,
            CreateConfirmedOrderCommand command, Instant now, String operatorId) {
        var schemeSnapshot = productSnapshots.resolveScheme(item.schemeSnapshotRef());
        String productSnapshotRef = schemeSnapshot.productSnapshotRef().snapshotBusinessId();
        ProductSnapshotRef schemeRef = item.schemeSnapshotRef();

        var basis = costBasis.resolveProductionBasis(
                schemeRef, item.targetProcess(), toMaterialAgreement(item.materialSources()));

        SettlementMoney amount = SettlementMoney.of(
                item.unitPrice().value().multiply(BigDecimal.valueOf(item.quantity())),
                item.unitPrice().currency());
        String orderItemId = "OIT-" + UUID.randomUUID();
        String groupId = "AGR-" + UUID.randomUUID();
        String versionId = "AGV-" + UUID.randomUUID();

        Map<String, Object> recipientPayload = new LinkedHashMap<>();
        recipientPayload.put("customerName", item.recipient().customerName());
        recipientPayload.put("phone", item.recipient().phone());
        recipientPayload.put("address", item.recipient().address());

        ProductionBasis productionBasis = toProductionBasis(basis);

        return new PreparedItem(
                itemNo, orderItemId, groupId, versionId, item, amount,
                productSnapshotRef, schemeRef, recipientPayload, productionBasis,
                new CreateProductionDemandRequest(
                        "orders", "ORDER_ITEM", orderItemId, orderBusinessId, orderItemId,
                        DemandType.NEW_PRODUCT, item.productBusinessId(), productSnapshotRef,
                        schemeSnapshot.schemeBusinessId(), schemeRef.snapshotBusinessId(),
                        productionBasis, item.quantity(), null));
    }

    private void writeItem(String orderBusinessId, PreparedItem prepared, Instant now) {
        OrderItemRequest item = prepared.item();
        orders.insertItem(new OrderRepository.ItemRecord(
                prepared.orderItemId(), orderBusinessId, prepared.itemNo(), item.productBusinessId(),
                prepared.productSnapshotRef(), item.quantity(), item.quantity(), "ACTIVE", 1L));
        orders.insertAgreementGroup(new OrderRepository.AgreementGroupRecord(
                prepared.groupId(), prepared.orderItemId(), 1, item.quantity(), 0, "ACTIVE", 1L));
        orders.insertAgreementVersion(new OrderRepository.AgreementVersionRecord(
                prepared.versionId(), prepared.groupId(), 1, item.schemeSnapshotRef().snapshotBusinessId(),
                item.targetProcess().name(), item.unitPrice().value(), item.quantity(),
                item.materialSources().glue().name(), item.materialSources().colorPaste().name(),
                item.materialSources().bag().name(), item.materialSources().decoration().name(),
                writeJson(prepared.basisPayload()), prepared.demandRequest().basis().fingerprint().value(),
                writeJson(prepared.recipientPayload()), null, prepared.versionId(),
                ContentFingerprint.of(sha256(prepared.versionId()
                        + item.schemeSnapshotRef().snapshotBusinessId() + item.quantity())).value(), now));
        // 同一工序的多条成本行必须聚合为一行约定工序，否则唯一键冲突
        Map<String, BigDecimal[]> aggregated = new LinkedHashMap<>();
        for (var line : prepared.productionBasis().costLines()) {
            String processCode = line.processCode() == null ? "EXTRA" : line.processCode().name();
            BigDecimal[] values = aggregated.computeIfAbsent(
                    processCode, key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            if ("LABOR".equals(line.nature())) {
                values[0] = values[0].add(line.fullResourceCost().value());
            } else {
                values[1] = values[1].add(line.fullResourceCost().value());
            }
        }
        for (var entry : aggregated.entrySet()) {
            var process = prepared.productionBasis().targetProcess();
            orders.insertAgreementProcess(new OrderRepository.AgreementProcessRecord(
                    prepared.versionId(), entry.getKey(),
                    standardMinutesOf(prepared.productionBasis(), process),
                    entry.getValue()[0], entry.getValue()[1], BigDecimal.ZERO, null));
        }
    }

    private static int standardMinutesOf(ProductionBasis basis, com.yumi.production.api.TargetProcess process) {
        if (process == null) {
            return 1;
        }
        return switch (process) {
            case MAKING -> basis.makingStandardMinutes();
            case BAGGING -> basis.baggingStandardMinutes();
            case SEAM_CUTTING -> basis.seamStandardMinutes();
        };
    }

    private static ProductCostBasisQuery.MaterialSourceAgreement toMaterialAgreement(
            OrderItemRequest.MaterialSourceAgreement sources) {
        return new ProductCostBasisQuery.MaterialSourceAgreement(
                sources.glue(), sources.colorPaste(), sources.bag(), sources.decoration());
    }

    private static ProductionBasis toProductionBasis(ProductCostBasisQuery.ResolvedProductionBasis basis) {
        return new ProductionBasis(
                basis.schemeSnapshotRef().snapshotBusinessId(),
                basis.productSnapshotRef().snapshotBusinessId(),
                basis.schemeBusinessId(),
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

    private OrderRepository.OrderRecord requireOrder(String orderBusinessId) {
        return orders.findOrder(orderBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在：" + orderBusinessId));
    }

    private OrderRef requireRef(String orderBusinessId) {
        OrderRepository.OrderRecord record = requireOrder(orderBusinessId);
        var items = orders.findItems(orderBusinessId);
        int totalQuantity = items.stream().mapToInt(OrderRepository.ItemRecord::activeQuantity).sum();
        return new OrderRef(
                record.orderBusinessId(), record.orderNo(), OrderStatus.valueOf(record.status()),
                totalQuantity, SettlementMoney.of(record.currentReceivableAmount(), CurrencyCode.CNY),
                record.contentVersion(), record.rowVersion());
    }

    private OrderView toView(OrderRepository.OrderRecord record) {
        List<OrderView.ItemView> items = new ArrayList<>();
        for (var item : orders.findItems(record.orderBusinessId())) {
            var group = orders.findGroup(item.orderItemBusinessId()).orElseThrow();
            var version = orders.findActiveVersion(
                    group.agreementGroupBusinessId(), group.activeVersionNo()).orElseThrow();
            items.add(new OrderView.ItemView(
                    item.orderItemBusinessId(), item.productBusinessId(), item.productSnapshotRef(),
                    item.orderedQuantity(), item.activeQuantity(), item.status(),
                    new OrderView.AgreementView(
                            group.agreementGroupBusinessId(), version.agreementVersionBusinessId(),
                            version.versionNo(), version.schemeSnapshotRef(), version.targetProcess(),
                            SettlementMoney.of(version.unitPrice(), CurrencyCode.CNY), version.quantity(),
                            group.fulfilledQuantity(), version.productionBasisFingerprint(),
                            readRecipient(version.recipientPayloadJson()))));
        }
        var projection = orders.findProjection(record.orderBusinessId()).orElseThrow();
        List<String> blocking = projection.blockingReasonCodes() == null
                ? List.of()
                : List.of(projection.blockingReasonCodes().split(","));
        return new OrderView(
                record.orderBusinessId(), record.orderNo(), OrderOrigin.valueOf(record.origin()),
                OrderStatus.valueOf(record.status()), record.customerBusinessId(),
                record.customerSnapshotRef(), record.orderDate(), record.expectedDeliveryDate(),
                SettlementMoney.of(record.currentReceivableAmount(), CurrencyCode.CNY),
                record.deliveryFulfilled(), record.paymentFulfilled(), items,
                new OrderView.FulfillmentView(
                        projection.requiredQuantity(), projection.shippedQuantity(),
                        SettlementMoney.of(projection.currentReceivable(), CurrencyCode.CNY),
                        SettlementMoney.of(projection.receivedAmount(), CurrencyCode.CNY),
                        SettlementMoney.of(projection.refundedAmount(), CurrencyCode.CNY),
                        projection.deliveryFulfilled(), projection.paymentFulfilled(), blocking));
    }

    private String fingerprint(CreateConfirmedOrderCommand command) {
        StringBuilder builder = new StringBuilder(command.customerBusinessId())
                .append('|').append(command.orderDate()).append('|').append(command.expectedDeliveryDate());
        for (OrderItemRequest item : command.items()) {
            builder.append('|').append(item.productBusinessId())
                    .append(':').append(item.schemeSnapshotRef().snapshotBusinessId())
                    .append(':').append(item.targetProcess())
                    .append(':').append(item.quantity())
                    .append(':').append(item.unitPrice().value().toPlainString())
                    .append(':').append(item.materialSources().glue())
                    .append(':').append(item.materialSources().colorPaste())
                    .append(':').append(item.materialSources().bag())
                    .append(':').append(item.materialSources().decoration());
        }
        return sha256(builder.toString());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "ORDER_PAYLOAD_INVALID", "订单约定内容无法序列化");
        }
    }

    private com.yumi.orders.api.RecipientInfo readRecipient(String json) {
        try {
            var node = objectMapper.readTree(json);
            return new com.yumi.orders.api.RecipientInfo(
                    node.path("customerName").asText(),
                    node.path("phone").asText(),
                    node.path("address").asText());
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "ORDER_PAYLOAD_INVALID", "收货信息无法解析");
        }
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

    /** 已准备并冻结的订单项。 */
    private record PreparedItem(
            int itemNo, String orderItemId, String groupId, String versionId, OrderItemRequest item,
            SettlementMoney amount, String productSnapshotRef, ProductSnapshotRef schemeRef,
            Map<String, Object> recipientPayload, ProductionBasis productionBasis,
            CreateProductionDemandRequest demandRequest) {

        /** 约定版本中的依据载荷（与生产需求使用同一依据）。 */
        Map<String, Object> basisPayload() {
            return Map.of(
                    "fingerprint", productionBasis.fingerprint().value(),
                    "targetProcess", productionBasis.targetProcess().name(),
                    "makingStandardMinutes", productionBasis.makingStandardMinutes(),
                    "baggingStandardMinutes", productionBasis.baggingStandardMinutes(),
                    "seamStandardMinutes", productionBasis.seamStandardMinutes());
        }
    }
}
