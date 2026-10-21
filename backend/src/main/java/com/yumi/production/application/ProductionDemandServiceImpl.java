package com.yumi.production.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.CreateProductionDemandRequest;
import com.yumi.production.api.DemandReductionPreview;
import com.yumi.production.api.DemandReductionResult;
import com.yumi.production.api.DemandStatus;
import com.yumi.production.api.ReduceProductionDemandCommand;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.PreparedDemand;
import com.yumi.production.api.ProductionBasis;
import com.yumi.production.api.ProductionDemandParticipant;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.SourceKind;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.ProductionRepository;
import com.yumi.production.application.port.QuantityProjections;
import com.yumi.production.application.port.ReturnedProductRepository;
import com.yumi.production.application.port.SourceRecord;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生产需求参与实现。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.1—§5.3、§5.5、§5.7：
 * <ul>
 *   <li>一个订单项一个需求；需求来源类型与需求类型分别保存，不互相替代；</li>
 *   <li>建立需求只写需求、履约来源与初始数量投影，不产生任务与排班；</li>
 *   <li>新制来源的物理根是自身；修复来源沿用退回记录携带的原始物理根；</li>
 *   <li>同一来源重复建立返回同一需求，数量不变（幂等）；</li>
 *   <li>既有订单期初接管在完成期初初始化前不得安排任务：来源初始状态为
 *       {@code PENDING_OPENING}，期初初始化后转 {@code OPENED}。</li>
 * </ul>
 */
public class ProductionDemandServiceImpl implements ProductionDemandParticipant {

    /** 已有订单来源在完成期初初始化前不得安排任务或消费。 */
    public static final String OPENING_PENDING = "PENDING_OPENING";

    /** 期初尚未完成标记（由订单期初接管命令设置）。 */
    public static final String OPENING_REQUIRED = "REQUIRES_OPENING";

    /** 期初已完成。 */
    public static final String OPENING_COMPLETED = "OPENED";

    /** 来源允许安排任务。 */
    public static final String SOURCE_ACTIVE = "ACTIVE";

    private final ProductionRepository repository;

    private final ReturnedProductRepository returnedProducts;

    private final AuthorizationService authorization;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public ProductionDemandServiceImpl(
            ProductionRepository repository,
            ReturnedProductRepository returnedProducts,
            AuthorizationService authorization,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.returnedProducts = returnedProducts;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PreparedDemand prepareCreate(CreateProductionDemandRequest request) {
        authorization.require(PermissionCodes.PRODUCTION_SCHEDULE, ResourceScope.GLOBAL);
        if (request == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DEMAND", "生产需求请求不能为空");
        }
        validateRequest(request);

        Optional<DemandRecord> existing = repository.findDemandBySource(
                request.sourceModule(), request.sourceType(), request.sourceBusinessId());
        if (existing.isPresent()) {
            DemandRecord record = existing.orElseThrow();
            requireSameDefinition(record, request);
            List<SourceRecord> sources = repository.findSourcesOfDemand(record.demandBusinessId());
            SourceRecord source = sources.isEmpty() ? null : sources.getFirst();
            return new PreparedDemand(
                    request,
                    record.demandBusinessId(),
                    source == null ? null : source.productionSourceBusinessId(),
                    source == null ? null : source.physicalRootSourceBusinessId(),
                    com.yumi.platform.api.value.ContentFingerprint.of(record.productionBasisFingerprint()));
        }

        if (request.orderItemBusinessId() != null) {
            repository.findDemandByOrderItem(request.orderItemBusinessId()).ifPresent(conflict -> {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_DEMAND_CONFLICT",
                        "同一订单项已存在生产需求：" + conflict.demandBusinessId());
            });
        }

        String demandBusinessId = "DMND-" + UUID.randomUUID();
        String productionSourceBusinessId = "PSRC-" + UUID.randomUUID();
        String physicalRoot = request.demandType() == DemandType.NEW_PRODUCT
                ? productionSourceBusinessId
                : requireReturnedPhysicalRoot(request.basisReturnedSourceBusinessId());

        return new PreparedDemand(
                request, demandBusinessId, productionSourceBusinessId, physicalRoot,
                request.basis().fingerprint());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductionDemandRef commitCreate(PreparedDemand prepared) {
        authorization.require(PermissionCodes.PRODUCTION_SCHEDULE, ResourceScope.GLOBAL);
        if (prepared == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_PREPARED", "需求准备结果不能为空");
        }
        CreateProductionDemandRequest request = prepared.request();

        // 幂等：同一来源已建立时返回既有需求
        Optional<DemandRecord> existing = repository.findDemandBySource(
                request.sourceModule(), request.sourceType(), request.sourceBusinessId());
        if (existing.isPresent()) {
            DemandRecord record = existing.orElseThrow();
            List<SourceRecord> sources = repository.findSourcesOfDemand(record.demandBusinessId());
            return toRef(record, sources.isEmpty() ? null : sources.getFirst());
        }

        var now = clock.instant();
        String basisJson = writeJson(request.basis());
        DemandRecord demand = new DemandRecord(
                0L,
                prepared.demandBusinessId(),
                request.demandType(),
                request.sourceModule(),
                request.sourceType(),
                request.sourceBusinessId(),
                request.orderBusinessId(),
                request.orderItemBusinessId(),
                request.productBusinessId(),
                request.productSnapshotRef(),
                request.schemeBusinessId(),
                request.schemeSnapshotRef(),
                request.basis().targetProcess(),
                request.basisReturnedSourceBusinessId(),
                basisJson,
                request.basis().fingerprint().value(),
                request.requiredQuantity(),
                DemandStatus.ACTIVE,
                1,
                1,
                now,
                now);
        repository.insertDemand(demand);

        boolean requiresOpening = "EXISTING_ORDER".equals(request.sourceType());
        SourceRecord source = new SourceRecord(
                0L,
                prepared.productionSourceBusinessId(),
                prepared.demandBusinessId(),
                request.demandType() == DemandType.NEW_PRODUCT
                        ? SourceKind.NEW_CREATION
                        : SourceKind.RETURNED_EXISTING_PRODUCT,
                prepared.physicalRootSourceBusinessId(),
                request.basisReturnedSourceBusinessId(),
                request.productBusinessId(),
                request.productSnapshotRef(),
                request.schemeBusinessId(),
                request.schemeSnapshotRef(),
                request.basis().fingerprint().value(),
                request.requiredQuantity(),
                request.basis().targetProcess(),
                requiresOpening ? OPENING_REQUIRED : OPENING_COMPLETED,
                requiresOpening ? OPENING_PENDING : SOURCE_ACTIVE,
                1L,
                now);
        repository.insertSource(source);

        // 初始投影：全部数量处于未制/待修复阶段，尚未形成任何生产事实
        repository.insertDemandProjection(new QuantityProjections.DemandProjection(
                prepared.demandBusinessId(),
                request.requiredQuantity(),
                request.requiredQuantity(),
                0,
                0,
                0,
                0,
                1L));
        // 物理制品投影：累计制作产生量只在 MAKING 核验合格后增加，建立需求时为零。
        // 修复需求沿用退回制品已存在的物理根投影，不新建也不清零。
        if (request.demandType() == DemandType.NEW_PRODUCT) {
            repository.insertPhysicalProjection(new QuantityProjections.PhysicalProjection(
                    prepared.physicalRootSourceBusinessId(), 0, 0, 0, 0, 0, 1L));
        }

        return toRef(demand, source);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DemandReductionPreview previewReduction(ReduceProductionDemandCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_SCHEDULE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_REDUCTION", "减量命令不能为空");
        }
        DemandRecord demand = requireDemand(command.demandBusinessId());
        SourceRecord source = repository.findSourcesOfDemand(demand.demandBusinessId()).stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_SOURCE_NOT_FOUND", "履约来源不存在"));
        var projection = repository.findDemandProjection(demand.demandBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_MISSING", "需求数量投影缺失"));

        // 移交已经随订单变更在同事务完成，因此“变更前数量”要把已移交部分加回
        int alreadyTransferred = command.transfers().stream()
                .mapToInt(ReduceProductionDemandCommand.TransferAllocation::quantity)
                .sum();
        int current = projection.requiredQuantity() + alreadyTransferred;
        int reduction = current - command.targetQuantity();
        int cancelUnproduced = reduction - alreadyTransferred;

        List<String> blocking = new ArrayList<>();
        if (reduction <= 0) {
            blocking.add("减量数量必须为正数，不能用减量命令增加需求");
        }
        if (projection.fulfilledQuantity() > 0) {
            blocking.add("存在已消费数量，必须先由下游更正并冲销消费");
        }
        if (cancelUnproduced < 0) {
            blocking.add("移交数量不能超过本次减量数量");
        }
        if (cancelUnproduced > projection.pendingWorkQuantity()) {
            blocking.add(cancelUnproduced > projection.pendingWorkQuantity()
                    && alreadyTransferred == 0
                    ? "已制减量必须同时提供线下移交明细，不能把物理制品当作未制数量取消"
                    : "未制数量不足以取消：需要 " + cancelUnproduced + "，可用 "
                            + projection.pendingWorkQuantity());
        }
        if (projection.inProcessQuantity() > 0 && command.targetQuantity() < projection.inProcessQuantity()) {
            blocking.add("在制数量不能通过减量删除：" + projection.inProcessQuantity());
        }
        if (alreadyTransferred > projection.targetReadyQuantity()) {
            blocking.add("移交数量超过目标就绪数量：" + projection.targetReadyQuantity());
        }

        return new DemandReductionPreview(
                demand.demandBusinessId(), source.productionSourceBusinessId(), current,
                command.targetQuantity(), Math.max(reduction, 0), Math.max(cancelUnproduced, 0),
                alreadyTransferred, projection.inProcessQuantity() + projection.fulfilledQuantity(),
                blocking);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DemandReductionResult commitReduction(ReduceProductionDemandCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_SCHEDULE, ResourceScope.GLOBAL);
        DemandReductionPreview preview = previewReduction(command);
        if (!preview.isAllowed()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_DEMAND_REDUCTION_BLOCKED",
                    "减量被拒绝：" + String.join("；", preview.blockingReasons()));
        }
        var projection = repository.findDemandProjection(command.demandBusinessId()).orElseThrow();
        int pending = projection.pendingWorkQuantity() - preview.cancelUnproducedQuantity();
        if (pending < 0) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_PROJECTION_INCONSISTENT",
                    "减量导致数量投影为负，拒绝提交");
        }
        // 迁移：required 直接取目标数量；已制部分已由移交移出目标就绪
        repository.insertDemandProjection(new QuantityProjections.DemandProjection(
                command.demandBusinessId(), command.targetQuantity(), pending,
                projection.inProcessQuantity(), projection.targetReadyQuantity(),
                projection.fulfilledQuantity(),
                projection.fulfillmentGapQuantity(), projection.rowVersion() + 1));

        // 需求根与投影必须一致：只改投影会让读模型与需求根分叉
        repository.updateDemandRequiredQuantity(command.demandBusinessId(), command.targetQuantity());

        String factId = "DCF-" + UUID.randomUUID();
        insertChangeFact(factId, command, preview, projection);
        return new DemandReductionResult(
                command.demandBusinessId(), factId, command.targetQuantity(),
                preview.cancelUnproducedQuantity(), preview.transferProducedQuantity());
    }

    private void insertChangeFact(
            String factId, ReduceProductionDemandCommand command, DemandReductionPreview preview,
            QuantityProjections.DemandProjection projection) {
        repository.insertDemandChangeFact(new ProductionRepository.DemandChangeFact(
                factId, command.demandBusinessId(), preview.productionSourceBusinessId(),
                "ORDER_REDUCTION", preview.currentQuantity(), command.targetQuantity(),
                -preview.reductionQuantity(), preview.cancelUnproducedQuantity(),
                preview.transferProducedQuantity(), 0, 0,
                command.reasonCode(), command.reasonText()));
    }

    private void validateRequest(CreateProductionDemandRequest request) {
        requireText(request.sourceModule(), "来源模块", 32);
        requireText(request.sourceType(), "来源类型", 32);
        requireText(request.sourceBusinessId(), "来源业务标识", 64);
        requireText(request.productBusinessId(), "产品编号", 64);
        requireText(request.productSnapshotRef(), "商品快照引用", 64);
        requireText(request.schemeBusinessId(), "方案编号", 64);
        requireText(request.schemeSnapshotRef(), "方案快照引用", 64);
        if (request.basis() == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_BASIS", "生产依据不能为空");
        }
        if (request.basis().fingerprint() == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "MISSING_BASIS", "生产依据指纹不能为空（依据必须来自商品解析）");
        }
        if (request.requiredQuantity() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCTION_QUANTITY_INVALID", "需求数量必须大于 0");
        }
    }

    private static void requireSameDefinition(DemandRecord record, CreateProductionDemandRequest request) {
        if (record.demandType() != request.demandType()
                || !record.productBusinessId().equals(request.productBusinessId())
                || !record.schemeSnapshotRef().equals(request.schemeSnapshotRef())
                || record.targetProcess() != request.basis().targetProcess()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_DEMAND_CONFLICT",
                    "同一来源已存在定义不同的生产需求");
        }
    }

    private String requireReturnedPhysicalRoot(String returnedSourceBusinessId) {
        if (returnedSourceBusinessId == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "MISSING_RETURNED_SOURCE", "修复需求必须关联退回制品来源");
        }
        // 修复需求必须沿用退回制品池记录的原始物理根，不能把池来源标识当作物理根
        return returnedProducts.findReturnedSource(returnedSourceBusinessId)
                .map(ReturnedProductRepository.ReturnedSourceRecord::physicalRootSourceBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_RETURNED_SOURCE_NOT_FOUND",
                        "退回制品来源不存在：" + returnedSourceBusinessId));
    }

    private String writeJson(ProductionBasis basis) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemeSnapshotRef", basis.schemeSnapshotRef());
        payload.put("productSnapshotRef", basis.productSnapshotRef());
        payload.put("schemeBusinessId", basis.schemeBusinessId());
        payload.put("targetProcess", basis.targetProcess().name());
        payload.put("glueSource", basis.materialSources().glue().name());
        payload.put("colorPasteSource", basis.materialSources().colorPaste().name());
        payload.put("bagSource", basis.materialSources().bag().name());
        payload.put("decorationSource", basis.materialSources().decoration().name());
        payload.put("formulaRuleVersion", basis.formulaRuleVersion());
        payload.put("roundingVersion", basis.roundingVersion());
        payload.put("fingerprint", basis.fingerprint().value());
        payload.put("makingStandardMinutes", basis.makingStandardMinutes());
        payload.put("baggingStandardMinutes", basis.baggingStandardMinutes());
        payload.put("seamStandardMinutes", basis.seamStandardMinutes());
        payload.put("costLines", basis.costLines().stream().map(line -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("componentCode", line.componentCode());
            entry.put("processCode", line.processCode() == null ? null : line.processCode().name());
            entry.put("nature", line.nature());
            entry.put("source", line.source().name());
            entry.put("quantity", line.quantity() == null ? null : line.quantity().toPlainString());
            entry.put("unitPrice", line.unitPrice() == null
                    ? null : line.unitPrice().value().toPlainString());
            entry.put("fullResourceCost", line.fullResourceCost().value().toPlainString());
            return entry;
        }).toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_BASIS_INVALID", "生产依据无法序列化");
        }
    }

    static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_FIELD", label + "不能为空");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "FIELD_TOO_LONG", label + "长度不能超过 " + maxLength);
        }
        return stripped;
    }

    private DemandRecord requireDemand(String demandBusinessId) {
        return repository.findDemand(demandBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCTION_DEMAND_NOT_FOUND", "生产需求不存在"));
    }

    private static ProductionDemandRef toRef(DemandRecord record, SourceRecord source) {
        return new ProductionDemandRef(
                record.demandBusinessId(),
                record.demandType(),
                source == null ? null : source.productionSourceBusinessId(),
                source == null ? null : source.physicalRootSourceBusinessId(),
                record.targetProcess(),
                record.requiredQuantity(),
                record.status(),
                record.rowVersion());
    }
}
