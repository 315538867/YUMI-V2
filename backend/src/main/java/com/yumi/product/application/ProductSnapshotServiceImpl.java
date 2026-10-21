package com.yumi.product.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.product.api.ProductSnapshotView;
import com.yumi.product.api.SchemeSnapshotView;
import com.yumi.product.api.SchemeView;
import com.yumi.product.application.port.BudgetRepository;
import com.yumi.product.application.port.ProductRecord;
import com.yumi.product.application.port.ProductSnapshotRecord;
import com.yumi.product.application.port.ProductSnapshotRepository;
import com.yumi.product.application.port.SchemeRecord;
import com.yumi.product.application.port.SchemeSnapshotRecord;
import com.yumi.product.infrastructure.JdbcProductSnapshotRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 商品与方案快照实现。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §9.5、§12：
 * <ul>
 *   <li>快照按内容版本冻结，相同版本重复取得返回同一引用；</li>
 *   <li>定义变更只产生新版本，旧快照内容不变；</li>
 *   <li>解析历史快照不受当前停用状态影响。</li>
 * </ul>
 */
public class ProductSnapshotServiceImpl implements ProductSnapshotService {

    private final ProductSnapshotRepository snapshots;

    private final ProductCommandService products;

    private final ProductSchemeCommandService schemes;

    private final AuthorizationService authorization;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public ProductSnapshotServiceImpl(
            ProductSnapshotRepository snapshots,
            ProductCommandService products,
            ProductSchemeCommandService schemes,
            AuthorizationService authorization,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snapshots = snapshots;
        this.products = products;
        this.schemes = schemes;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public ProductSnapshotRef acquireProductSnapshot(String productBusinessId) {
        authorization.require(PermissionCodes.PRODUCT_SNAPSHOT_READ, ResourceScope.GLOBAL);
        ProductRecord product = products.requireProduct(productBusinessId);
        return snapshots.findProductSnapshot(product.productBusinessId(), product.contentVersion())
                .map(ProductSnapshotServiceImpl::toRef)
                .orElseGet(() -> createProductSnapshot(product));
    }

    @Override
    public SchemeSnapshotRefAndView acquireSchemeSnapshot(String schemeBusinessId) {
        authorization.require(PermissionCodes.PRODUCT_SNAPSHOT_READ, ResourceScope.GLOBAL);
        SchemeRecord scheme = schemes.requireScheme(schemeBusinessId);
        ProductRecord product = products.requireProduct(scheme.productBusinessId());
        ProductSnapshotRef productSnapshot = acquireProductSnapshot(product.productBusinessId());

        return snapshots.findSchemeSnapshot(
                        scheme.schemeBusinessId(), scheme.contentVersion(), product.contentVersion())
                .map(record -> new SchemeSnapshotRefAndView(toRef(record), toSchemeView(record)))
                .orElseGet(() -> createSchemeSnapshot(scheme, product, productSnapshot));
    }

    @Override
    public ProductSnapshotView resolveProduct(ProductSnapshotRef ref) {
        authorization.require(PermissionCodes.PRODUCT_SNAPSHOT_READ, ResourceScope.GLOBAL);
        if (ref == null || ref.snapshotType() != ProductSnapshotRef.SnapshotType.PRODUCT) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_SNAPSHOT_INVALID", "需要商品快照引用");
        }
        ProductSnapshotRecord record = snapshots.findProductSnapshotByBusinessId(ref.snapshotBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_SNAPSHOT_INVALID", "商品快照不存在"));
        return toProductView(record);
    }

    @Override
    public SchemeSnapshotView resolveScheme(ProductSnapshotRef ref) {
        authorization.require(PermissionCodes.PRODUCT_SNAPSHOT_READ, ResourceScope.GLOBAL);
        if (ref == null || ref.snapshotType() != ProductSnapshotRef.SnapshotType.SCHEME) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_SNAPSHOT_INVALID", "需要方案快照引用");
        }
        SchemeSnapshotRecord record = snapshots.findSchemeSnapshotByBusinessId(ref.snapshotBusinessId())
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_SNAPSHOT_INVALID", "方案快照不存在"));
        return toSchemeView(record);
    }

    private ProductSnapshotRef createProductSnapshot(ProductRecord product) {
        ProductRecord.ProcessCosts costs = product.processCosts();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("productNo", product.productNo());
        base.put("name", product.name());
        base.put("standardWeightGrams", product.standardWeightGrams().toPlainString());
        base.put("makingStandardMinutes", product.makingStandardMinutes());
        base.put("dailyCapacity", product.dailyCapacity());
        base.put("standardSalePrice", product.standardSalePrice().toPlainString());
        base.put("contentVersion", product.contentVersion());

        Map<String, Object> processes = new LinkedHashMap<>();
        processes.put("makingStandardMinutes", product.makingStandardMinutes());
        processes.put("makingCommission", product.makingCommission().toPlainString());
        processes.put("baggingStandardMinutes", costs.baggingStandardMinutes());
        processes.put("baggingLaborCost", costs.baggingLaborCost().toPlainString());
        processes.put("seamStandardMinutes", costs.seamStandardMinutes());
        processes.put("seamLaborCost", costs.seamLaborCost().toPlainString());

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("otherTaskLaborCost", costs.extraOtherTaskLaborCost().toPlainString());
        extra.put("transportPackagingCost", costs.extraTransportPackagingCost().toPlainString());
        extra.put("dailyMiscCost", costs.extraDailyMiscCost().toPlainString());
        extra.put("rentUtilitiesCost", costs.extraRentUtilitiesCost().toPlainString());
        extra.put("mouldUnitAmortizationCost", costs.extraMouldAmortizationCost().toPlainString());
        extra.put("otherCost", costs.extraOtherCost().toPlainString());

        String fingerprint = sha256(
                product.productBusinessId() + "|" + product.contentVersion() + "|"
                        + toJson(base) + "|" + toJson(processes) + "|" + toJson(extra));

        ProductSnapshotRecord record = new ProductSnapshotRecord(
                "PSNAP-" + UUID.randomUUID(),
                product.productBusinessId(),
                snapshots.maxProductSnapshotVersion(product.productBusinessId()) + 1,
                product.contentVersion(),
                toJson(base),
                toJson(processes),
                toJson(extra),
                product.dailyCapacity(),
                ContentFingerprint.of(fingerprint),
                clock.instant());

        try {
            snapshots.insertProductSnapshot(record);
            return toRef(record);
        } catch (JdbcProductSnapshotRepository.SnapshotConflict conflict) {
            return snapshots.findProductSnapshot(product.productBusinessId(), product.contentVersion())
                    .map(ProductSnapshotServiceImpl::toRef)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_SNAPSHOT_CONFLICT",
                            "商品快照并发创建冲突，请重试"));
        }
    }

    private SchemeSnapshotRefAndView createSchemeSnapshot(
            SchemeRecord scheme, ProductRecord product, ProductSnapshotRef productSnapshot) {
        BudgetRepository.BudgetRecord budget = schemes.requireBudget(scheme.schemeBusinessId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemeBusinessId", scheme.schemeBusinessId());
        payload.put("schemeType", scheme.schemeType().name());
        payload.put("name", scheme.name());
        payload.put("standardWeightGrams", scheme.standardWeightGrams().toPlainString());
        payload.put("targetProcess", scheme.targetProcess());
        payload.put("salePrice", scheme.salePrice().toPlainString());
        payload.put("glueSource", scheme.glueSource());
        payload.put("colorPasteSource", scheme.colorPasteSource());
        payload.put("makingStandardMinutes", scheme.costs().makingStandardMinutes());
        payload.put("makingCommission", scheme.costs().makingCommission().toPlainString());
        payload.put("baggingStandardMinutes", scheme.costs().baggingStandardMinutes());
        payload.put("baggingLaborCost", scheme.costs().baggingLaborCost().toPlainString());
        payload.put("baggingBagSource", scheme.costs().baggingBagSource());
        payload.put("baggingDecorationSource", scheme.costs().baggingDecorationSource());
        payload.put("seamStandardMinutes", scheme.costs().seamStandardMinutes());
        payload.put("seamLaborCost", scheme.costs().seamLaborCost().toPlainString());
        payload.put("otherTaskLaborCost", scheme.costs().extraOtherTaskLaborCost().toPlainString());
        payload.put("productBusinessId", scheme.productBusinessId());
        payload.put("productSnapshotBusinessId", productSnapshot.snapshotBusinessId());

        Map<String, Object> budgetPayload = new LinkedHashMap<>();
        budgetPayload.put("fullResourceUnitCost", budget.fullResourceUnitCost().toPlainString());
        budgetPayload.put("studioBorneUnitCost", budget.studioBorneUnitCost().toPlainString());
        budgetPayload.put("customerSuppliedUnitValue", budget.customerSuppliedUnitValue().toPlainString());
        budgetPayload.put("expectedUnitProfit", budget.expectedUnitProfit().toPlainString());
        budgetPayload.put("expectedProfitRate",
                budget.expectedProfitRate() == null ? null : budget.expectedProfitRate().toPlainString());
        budgetPayload.put("formulaRuleVersion", budget.formulaRuleVersion());
        budgetPayload.put("roundingVersion", budget.roundingVersion());
        budgetPayload.put("inputFingerprint", budget.inputFingerprint());
        budgetPayload.put("lines", budget.lines().stream().map(line -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("componentCode", line.componentCode());
            entry.put("processCode", line.processCode());
            entry.put("nature", line.costNature());
            entry.put("bearer", line.bearerType());
            entry.put("quantity", line.quantity().toPlainString());
            entry.put("unitPrice", line.unitPrice().toPlainString());
            entry.put("fullResourceCost", line.fullResourceCost().toPlainString());
            entry.put("studioBorneCost", line.studioBorneCost().toPlainString());
            return entry;
        }).toList());

        String fingerprint = sha256(
                scheme.schemeBusinessId() + "|" + scheme.contentVersion() + "|"
                        + product.contentVersion() + "|" + toJson(payload) + "|" + toJson(budgetPayload));

        SchemeSnapshotRecord record = new SchemeSnapshotRecord(
                "SSNAP-" + UUID.randomUUID(),
                scheme.schemeBusinessId(),
                snapshots.maxSchemeSnapshotVersion(scheme.schemeBusinessId()) + 1,
                scheme.contentVersion(),
                product.contentVersion(),
                productSnapshot.snapshotBusinessId(),
                toJson(payload),
                toJson(budgetPayload),
                ContentFingerprint.of(fingerprint),
                clock.instant());

        try {
            snapshots.insertSchemeSnapshot(record);
            return new SchemeSnapshotRefAndView(toRef(record), toSchemeView(record));
        } catch (JdbcProductSnapshotRepository.SnapshotConflict conflict) {
            return snapshots.findSchemeSnapshot(
                            scheme.schemeBusinessId(), scheme.contentVersion(), product.contentVersion())
                    .map(existing -> new SchemeSnapshotRefAndView(toRef(existing), toSchemeView(existing)))
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_SNAPSHOT_CONFLICT",
                            "方案快照并发创建冲突，请重试"));
        }
    }

    private ProductSnapshotView toProductView(ProductSnapshotRecord record) {
        Map<String, Object> base = readJson(record.basePayloadJson());
        Map<String, Object> processes = readJson(record.processPayloadJson());
        Map<String, Object> extra = readJson(record.extraCostPayloadJson());
        return new ProductSnapshotView(
                toRef(record),
                text(base, "productNo"),
                text(base, "name"),
                com.yumi.platform.api.value.WeightGram.ofGrams(new java.math.BigDecimal(
                        text(base, "standardWeightGrams"))),
                number(processes, "makingStandardMinutes"),
                number(processes, "baggingStandardMinutes"),
                number(processes, "seamStandardMinutes"),
                cost(processes, "makingCommission"),
                cost(processes, "baggingLaborCost"),
                cost(processes, "seamLaborCost"),
                cost(extra, "otherTaskLaborCost"),
                record.dailyCapacity());
    }

    private SchemeSnapshotView toSchemeView(SchemeSnapshotRecord record) {
        Map<String, Object> payload = readJson(record.completeSchemePayloadJson());
        Map<String, Object> budgetPayload = readJson(record.budgetPayloadJson());
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> lines = (java.util.List<Map<String, Object>>)
                budgetPayload.getOrDefault("lines", java.util.List.of());
        SchemeView.SchemeBudgetView budget = new SchemeView.SchemeBudgetView(
                com.yumi.platform.api.value.CostAmount.of(
                        new java.math.BigDecimal(text(budgetPayload, "fullResourceUnitCost")),
                        com.yumi.platform.api.value.CurrencyCode.CNY),
                com.yumi.platform.api.value.CostAmount.of(
                        new java.math.BigDecimal(text(budgetPayload, "studioBorneUnitCost")),
                        com.yumi.platform.api.value.CurrencyCode.CNY),
                com.yumi.platform.api.value.CostAmount.of(
                        new java.math.BigDecimal(text(budgetPayload, "customerSuppliedUnitValue")),
                        com.yumi.platform.api.value.CurrencyCode.CNY),
                com.yumi.platform.api.value.SignedCostAmount.of(
                        new java.math.BigDecimal(text(budgetPayload, "expectedUnitProfit"))),
                java.util.Optional.ofNullable(budgetPayload.get("expectedProfitRate"))
                        .map(value -> new java.math.BigDecimal(value.toString())),
                text(budgetPayload, "formulaRuleVersion"),
                text(budgetPayload, "roundingVersion"),
                text(budgetPayload, "inputFingerprint"),
                lines.stream().map(line -> new SchemeView.BudgetLineView(
                        text(line, "componentCode"),
                        line.get("processCode") == null ? null : line.get("processCode").toString(),
                        text(line, "nature"),
                        text(line, "bearer"),
                        new java.math.BigDecimal(line.get("quantity") == null
                                ? "0" : line.get("quantity").toString()),
                        com.yumi.platform.api.value.CostAmount.of(
                                new java.math.BigDecimal(line.get("unitPrice") == null
                                        ? "0" : line.get("unitPrice").toString()),
                                com.yumi.platform.api.value.CurrencyCode.CNY),
                        com.yumi.platform.api.value.CostAmount.of(
                                new java.math.BigDecimal(text(line, "fullResourceCost")),
                                com.yumi.platform.api.value.CurrencyCode.CNY),
                        com.yumi.platform.api.value.CostAmount.of(
                                new java.math.BigDecimal(text(line, "studioBorneCost")),
                                com.yumi.platform.api.value.CurrencyCode.CNY))).toList());

        return new SchemeSnapshotView(
                toRef(record),
                text(payload, "schemeBusinessId"),
                com.yumi.product.api.SchemeType.valueOf(text(payload, "schemeType")),
                text(payload, "name"),
                com.yumi.platform.api.value.WeightGram.ofGrams(
                        new java.math.BigDecimal(text(payload, "standardWeightGrams"))),
                SchemeView.ProcessCode.valueOf(text(payload, "targetProcess")),
                com.yumi.platform.api.value.SettlementMoney.of(
                        new java.math.BigDecimal(text(payload, "salePrice")),
                        com.yumi.platform.api.value.CurrencyCode.CNY),
                MaterialSource.valueOf(text(payload, "glueSource")),
                MaterialSource.valueOf(text(payload, "colorPasteSource")),
                number(payload, "makingStandardMinutes"),
                cost(payload, "makingCommission"),
                number(payload, "baggingStandardMinutes"),
                cost(payload, "baggingLaborCost"),
                number(payload, "seamStandardMinutes"),
                cost(payload, "seamLaborCost"),
                cost(payload, "otherTaskLaborCost"),
                new ProductSnapshotRef(
                        ProductSnapshotRef.SnapshotType.PRODUCT,
                        text(payload, "productSnapshotBusinessId"),
                        text(payload, "productBusinessId"),
                        0L,
                        0L,
                        record.contentFingerprint()),
                budget);
    }

    private static com.yumi.platform.api.value.CostAmount cost(Map<String, Object> payload, String key) {
        return com.yumi.platform.api.value.CostAmount.of(
                new java.math.BigDecimal(text(payload, key)),
                com.yumi.platform.api.value.CurrencyCode.CNY);
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCT_SNAPSHOT_INVALID",
                    "快照缺少字段：" + key);
        }
        return value.toString();
    }

    private static int number(Map<String, Object> payload, String key) {
        return Integer.parseInt(text(payload, key));
    }

    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCT_SNAPSHOT_INVALID", "快照内容无法解析");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCT_SNAPSHOT_INVALID", "快照内容无法序列化");
        }
    }

    private static ProductSnapshotRef toRef(ProductSnapshotRecord record) {
        return new ProductSnapshotRef(
                ProductSnapshotRef.SnapshotType.PRODUCT,
                record.snapshotBusinessId(),
                record.productBusinessId(),
                record.snapshotVersion(),
                record.sourceContentVersion(),
                record.contentFingerprint());
    }

    private static ProductSnapshotRef toRef(SchemeSnapshotRecord record) {
        return new ProductSnapshotRef(
                ProductSnapshotRef.SnapshotType.SCHEME,
                record.snapshotBusinessId(),
                record.schemeBusinessId(),
                record.snapshotVersion(),
                record.sourceSchemeContentVersion(),
                record.contentFingerprint());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法", exception);
        }
    }
}
