package com.yumi.product.application;

import com.yumi.calculation.api.DailyCapacityResult;
import com.yumi.calculation.api.ProductBudgetResult;
import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.numbering.BusinessNumberService;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.product.api.ChangeProductStatusCommand;
import com.yumi.product.api.CreateProductCommand;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductApplicationService;
import com.yumi.product.api.ProductQuery;
import com.yumi.product.api.ProductRef;
import com.yumi.product.api.ProductStatus;
import com.yumi.product.api.ProductView;
import com.yumi.product.api.SchemeType;
import com.yumi.product.api.UpdateProductCommand;
import com.yumi.product.application.port.BudgetRepository;
import com.yumi.product.application.port.ProductRecord;
import com.yumi.product.application.port.ProductRepository;
import com.yumi.product.application.port.SchemeRecord;
import com.yumi.product.application.port.SchemeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 商品应用服务：完整提交、内置标准方案与预算同步、启停与查询。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §5、§10、§12、§13：
 * <ul>
 *   <li>商品保存时同步生成完整标准方案与预算，任一步失败整笔回滚；</li>
 *   <li>商品<b>不接收</b>目标工序；标准售价保留在商品上并由内置方案引用；</li>
 *   <li>录入默认值只在录入时预填，商品保存后使用自身内容版本；</li>
 *   <li>编辑使用行版本校验，冲突明确拒绝；</li>
 *   <li>停用只禁止新业务引用，不影响历史订单与快照解析。</li>
 * </ul>
 */
public class ProductCommandService implements ProductApplicationService, ProductQuery {

    /** 内置标准方案固定覆盖三道工序。 */
    public static final String SYSTEM_SCHEME_TARGET_PROCESS = "SEAM_CUTTING";

    /** 内置标准方案名称。 */
    public static final String SYSTEM_SCHEME_NAME = "标准方案";

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");

    private final ProductRepository products;

    private final SchemeRepository schemes;

    private final BudgetRepository budgets;

    private final BusinessNumberService numbering;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final AuditService audit;

    private final ProductBudgetMapper budgetMapper;

    private final Clock clock;

    private final TransactionTemplate transactionTemplate;

    public ProductCommandService(
            ProductRepository products,
            SchemeRepository schemes,
            BudgetRepository budgets,
            BusinessNumberService numbering,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            ProductBudgetMapper budgetMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.products = products;
        this.schemes = schemes;
        this.budgets = budgets;
        this.numbering = numbering;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.budgetMapper = budgetMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ProductRef create(CreateProductCommand command) {
        authorization.require(PermissionCodes.PRODUCT_CREATE, ResourceScope.GLOBAL);
        ValidatedDefinition definition = validate(command);

        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
            DailyCapacityResult capacity = budgetMapper.capacity(
                    definition.mouldCount, definition.mouldDailyBatches);

            ProductRecord product = definition.toRecord(
                    "PRD-" + UUID.randomUUID(),
                    numbering.nextNumber("PRODUCT", "P-", 6),
                    capacity.maxDailyUnits(),
                    ProductStatus.ACTIVE,
                    now,
                    operatorId);
            products.insert(product);

            SchemeRecord standard = buildSystemScheme(product, now);
            schemes.insert(standard);
            recalculateBudget(product, standard, now);

            recordAudit("CREATE_PRODUCT", product.productBusinessId(),
                    Map.of("productNo", product.productNo(), "name", product.name()));
            return toRef(product);
        });
    }

    @Override
    public ProductRef update(UpdateProductCommand command) {
        authorization.require(PermissionCodes.PRODUCT_UPDATE, ResourceScope.GLOBAL);
        if (command == null || command.definition() == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DEFINITION", "商品定义不能为空");
        }
        String productBusinessId = requireText(command.productBusinessId(), "商品标识", 64);
        ValidatedDefinition definition = validate(command.definition());

        return transactionTemplate.execute(status -> {
            ProductRecord existing = products.findByBusinessId(productBusinessId)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在"));

            Instant now = clock.instant();
            String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
            DailyCapacityResult capacity = budgetMapper.capacity(
                    definition.mouldCount, definition.mouldDailyBatches);

            ProductRecord updated = definition.toRecord(
                    existing.productBusinessId(), existing.productNo(), capacity.maxDailyUnits(),
                    existing.status(), existing.createdAt(), existing.createdBy());
            if (!products.update(updated, command.expectedRowVersion(), now, operatorId)) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_VERSION_CONFLICT",
                        "商品已被其他操作修改，请重新打开后再保存");
            }

            ProductRecord reloaded = products.findByBusinessId(productBusinessId).orElseThrow();
            syncSchemesAfterProductChange(reloaded, now);
            recordAudit("UPDATE_PRODUCT", productBusinessId,
                    Map.of("productNo", reloaded.productNo(), "contentVersion",
                            String.valueOf(reloaded.contentVersion())));
            return toRef(reloaded);
        });
    }

    @Override
    public ProductRef changeStatus(ChangeProductStatusCommand command) {
        authorization.require(PermissionCodes.PRODUCT_DEACTIVATE, ResourceScope.GLOBAL);
        if (command == null || command.targetStatus() == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_STATUS", "目标状态不能为空");
        }
        String productBusinessId = requireText(command.productBusinessId(), "商品标识", 64);

        return transactionTemplate.execute(status -> {
            ProductRecord existing = products.findByBusinessId(productBusinessId)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在"));
            if (existing.status() == command.targetStatus()) {
                return toRef(existing);
            }

            Instant now = clock.instant();
            String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
            if (!products.updateStatus(
                    productBusinessId, command.targetStatus(), command.expectedRowVersion(), now, operatorId)) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_VERSION_CONFLICT",
                        "商品已被其他操作修改，请重新打开后再操作");
            }

            ProductRecord reloaded = products.findByBusinessId(productBusinessId).orElseThrow();
            // 内置方案随商品状态，不单独停用
            schemes.findStandardOfProduct(productBusinessId).ifPresent(standard ->
                    schemes.updateStatus(
                            standard.schemeBusinessId(), command.targetStatus().name(),
                            standard.rowVersion(), now));
            recordAudit("CHANGE_PRODUCT_STATUS", productBusinessId,
                    Map.of("targetStatus", command.targetStatus().name()));
            return toRef(reloaded);
        });
    }

    @Override
    public ProductView getProduct(String productBusinessId) {
        authorization.require(PermissionCodes.PRODUCT_READ, ResourceScope.GLOBAL);
        return toView(products.findByBusinessId(requireText(productBusinessId, "商品标识", 64))
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在")));
    }

    @Override
    public List<ProductView> search(String keyword, ProductStatus status, int page, int size) {
        authorization.require(PermissionCodes.PRODUCT_READ, ResourceScope.GLOBAL);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return products.search(keyword, status, (safePage - 1) * safeSize, safeSize).stream()
                .map(ProductCommandService::toView)
                .toList();
    }

    /** 供方案服务复用：读取商品并校验存在（调用方已校验权限）。 */
    public ProductRecord requireProduct(String productBusinessId) {
        return products.findByBusinessId(productBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在"));
    }

    /** 供方案服务复用：商品为停用时拒绝新方案引用。 */
    public void requireActiveProduct(ProductRecord product) {
        if (product.status() != ProductStatus.ACTIVE) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCT_INACTIVE", "商品已停用，不能用于新业务");
        }
    }

    /** 按商品当前内容重建内置方案并重算预算；商品字段变化时调用。 */
    public void syncSchemesAfterProductChange(ProductRecord product, Instant now) {
        SchemeRecord standard = schemes.findStandardOfProduct(product.productBusinessId())
                .orElseThrow(() -> new IllegalStateException("商品缺少内置标准方案"));
        SchemeRecord synced = withInheritedValues(standard, product, now);
        schemes.update(synced, standard.rowVersion(), now);
        recalculateBudget(product, synced, now);

        // 自定义方案：继承字段随商品同步；内容版本递增，预算重算
        for (SchemeRecord custom : schemes.findAllOfProduct(product.productBusinessId())) {
            if (custom.schemeType() == SchemeType.SYSTEM_STANDARD) {
                continue;
            }
            SchemeRecord refreshed = withInheritedValues(custom, product, now);
            schemes.update(refreshed, custom.rowVersion(), now);
            recalculateBudget(product, refreshed, now);
        }
    }

    /** 重算并保存方案预算；失败抛异常，由事务整体回滚。 */
    public void recalculateBudget(ProductRecord product, SchemeRecord scheme, Instant now) {
        ProductBudgetResult result;
        try {
            result = budgetMapper.calculateBudget(product, scheme);
        } catch (RuntimeException failure) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCT_BUDGET_CALCULATION_FAILED",
                    "商品预算计算失败：" + failure.getMessage());
        }
        BudgetRepository.BudgetRecord record = budgetMapper.toRecord(
                "PBG-" + UUID.randomUUID(),
                product.contentVersion(),
                scheme.contentVersion(),
                result,
                scheme.schemeBusinessId());
        budgets.replace(record);
    }

    private SchemeRecord buildSystemScheme(ProductRecord product, Instant now) {
        ProductRecord.ProcessCosts costs = product.processCosts();
        return new SchemeRecord(
                0L,
                "SCH-" + UUID.randomUUID(),
                product.productBusinessId(),
                SchemeType.SYSTEM_STANDARD,
                SYSTEM_SCHEME_NAME,
                product.standardWeightGrams(),
                SYSTEM_SCHEME_TARGET_PROCESS,
                product.standardSalePrice(),
                MaterialSource.STUDIO.name(),
                MaterialSource.STUDIO.name(),
                ProductStatus.ACTIVE,
                product.contentVersion(),
                1,
                1,
                now,
                now,
                new SchemeRecord.SchemeCosts(
                        product.makingStandardMinutes(),
                        product.makingCommission(),
                        costs.makingOtherMaterialCost(),
                        costs.baggingStandardMinutes(),
                        costs.baggingLaborCost(),
                        costs.baggingBagUnitCost(),
                        MaterialSource.STUDIO.name(),
                        costs.baggingDecorationUnitPrice(),
                        MaterialSource.STUDIO.name(),
                        costs.baggingOtherCost(),
                        costs.seamStandardMinutes(),
                        costs.seamLaborCost(),
                        costs.seamCombinedMaterialCost(),
                        costs.seamOtherCost(),
                        costs.extraOtherTaskLaborCost(),
                        costs.extraTransportPackagingCost(),
                        costs.extraDailyMiscCost(),
                        costs.extraRentUtilitiesCost(),
                        costs.extraMouldAmortizationCost(),
                        costs.extraOtherCost()));
    }

    /**
     * 用商品当前值刷新方案中的继承字段。
     *
     * <p>人工、工时、提成与额外单件费用只能来自商品；目标工序、售价与供料方保留方案自身选择。
     */
    static SchemeRecord withInheritedValues(SchemeRecord scheme, ProductRecord product, Instant now) {
        ProductRecord.ProcessCosts costs = product.processCosts();
        return new SchemeRecord(
                scheme.id(),
                scheme.schemeBusinessId(),
                scheme.productBusinessId(),
                scheme.schemeType(),
                scheme.name(),
                product.standardWeightGrams(),
                scheme.targetProcess(),
                scheme.schemeType() == SchemeType.SYSTEM_STANDARD
                        ? product.standardSalePrice() : scheme.salePrice(),
                scheme.glueSource(),
                scheme.colorPasteSource(),
                scheme.status(),
                product.contentVersion(),
                scheme.contentVersion(),
                scheme.rowVersion(),
                scheme.createdAt(),
                now,
                new SchemeRecord.SchemeCosts(
                        product.makingStandardMinutes(),
                        product.makingCommission(),
                        scheme.costs().makingOtherMaterialCost(),
                        costs.baggingStandardMinutes(),
                        costs.baggingLaborCost(),
                        scheme.costs().baggingBagUnitCost(),
                        scheme.costs().baggingBagSource(),
                        scheme.costs().baggingDecorationUnitPrice(),
                        scheme.costs().baggingDecorationSource(),
                        scheme.costs().baggingOtherCost(),
                        costs.seamStandardMinutes(),
                        costs.seamLaborCost(),
                        scheme.costs().seamCombinedMaterialCost(),
                        scheme.costs().seamOtherCost(),
                        costs.extraOtherTaskLaborCost(),
                        costs.extraTransportPackagingCost(),
                        costs.extraDailyMiscCost(),
                        costs.extraRentUtilitiesCost(),
                        costs.extraMouldAmortizationCost(),
                        costs.extraOtherCost()));
    }

    private ValidatedDefinition validate(CreateProductCommand command) {
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_DEFINITION", "商品定义不能为空");
        }
        String name = requireText(command.name(), "商品名称", 80);
        String description = command.description() == null ? null : command.description().strip();
        if (description != null && description.isEmpty()) {
            description = null;
        }
        if (description != null && description.length() > 500) {
            throw new ApiException(ApiErrorType.VALIDATION, "FIELD_TOO_LONG", "商品说明长度不能超过 500");
        }
        if (command.standardWeightGrams() == null || command.standardWeightGrams().value().signum() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "单件标准克重必须大于 0");
        }
        String starCode = requireText(command.makingStarCode(), "制作星级", 32);
        if (command.makingStandardMinutes() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "单件标准制作分钟必须大于 0");
        }
        if (command.standardSalePrice() == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "标准售价不能为空");
        }
        if (command.glueLossRate() == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "胶水损耗率不能为空");
        }
        requireNonNegative(command.glueUnitPrice(), "胶水单价");
        requireNonNegative(command.colorPasteUnitPrice(), "色浆单价");
        requireNonNegative(command.makingCommission(), "制作提成");
        if (command.mouldCount() <= 0) {
            throw new ApiException(ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "模具数量必须大于 0");
        }
        if (command.mouldDailyBatches() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "模具每日生产批次必须大于 0");
        }
        requireNonNegative(command.makingOtherMaterialCost(), "制作工序其他材料费用");
        if (command.baggingStandardMinutes() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "捏毛装袋标准分钟必须大于 0");
        }
        requireNonNegative(command.baggingLaborCost(), "捏毛装袋人工成本");
        requireNonNegative(command.baggingBagUnitCost(), "单件袋子成本");
        requireNonNegative(command.baggingDecorationUnitPrice(), "单件装饰品价格");
        requireNonNegative(command.baggingOtherCost(), "捏毛装袋其他费用");
        if (command.seamStandardMinutes() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "缝边剪袋标准分钟必须大于 0");
        }
        requireNonNegative(command.seamLaborCost(), "缝边剪袋人工成本");
        requireNonNegative(command.seamCombinedMaterialCost(), "单件综合材料成本");
        requireNonNegative(command.seamOtherCost(), "缝边剪袋其他费用");
        requireNonNegative(command.extraOtherTaskLaborCost(), "其他任务人工费");
        requireNonNegative(command.extraTransportPackagingCost(), "运输包装费");
        requireNonNegative(command.extraDailyMiscCost(), "日常杂费");
        requireNonNegative(command.extraRentUtilitiesCost(), "房租水电费");
        requireNonNegative(command.extraMouldAmortizationCost(), "模具单件摊销费");
        requireNonNegative(command.extraOtherCost(), "其他费用");

        return new ValidatedDefinition(
                name, description, command.standardWeightGrams().value(), starCode,
                command.makingStandardMinutes(), command.standardSalePrice().value(),
                command.glueLossRate().value(), command.glueUnitPrice().value(),
                command.colorPasteUnitPrice().value(), command.makingCommission().value(),
                command.mouldCount(), command.mouldDailyBatches(),
                command.makingOtherMaterialCost().value(),
                command.baggingStandardMinutes(), command.baggingLaborCost().value(),
                command.baggingBagUnitCost().value(), command.baggingDecorationUnitPrice().value(),
                command.baggingOtherCost().value(),
                command.seamStandardMinutes(), command.seamLaborCost().value(),
                command.seamCombinedMaterialCost().value(), command.seamOtherCost().value(),
                command.extraOtherTaskLaborCost().value(), command.extraTransportPackagingCost().value(),
                command.extraDailyMiscCost().value(), command.extraRentUtilitiesCost().value(),
                command.extraMouldAmortizationCost().value(), command.extraOtherCost().value());
    }

    private static void requireNonNegative(CostAmount amount, String label) {
        if (amount == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", label + "不能为空");
        }
        if (amount.value().signum() < 0) {
            throw new ApiException(ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", label + "不允许为负");
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
        if (CONTROL_CHARS.matcher(stripped).find()) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "INVALID_CHARACTERS", label + "不能包含换行或控制字符");
        }
        return stripped;
    }

    private void recordAudit(String actionCode, String targetBusinessId, Map<String, String> detail) {
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(),
                currentActor.requireCurrentActor().accountBusinessId().value(),
                "product",
                actionCode,
                "PRODUCT",
                targetBusinessId,
                "SUCCESS",
                new LinkedHashMap<>(detail),
                clock.instant()));
    }

    private static ProductRef toRef(ProductRecord record) {
        return new ProductRef(
                record.productBusinessId(), record.productNo(), record.status(),
                record.contentVersion(), record.rowVersion(), record.dailyCapacity());
    }

    private static ProductView toView(ProductRecord record) {
        ProductRecord.ProcessCosts costs = record.processCosts();
        return new ProductView(
                record.productBusinessId(),
                record.productNo(),
                record.name(),
                record.description(),
                com.yumi.platform.api.value.WeightGram.ofGrams(record.standardWeightGrams()),
                record.makingStarCode(),
                record.makingStarName(),
                record.makingStandardMinutes(),
                com.yumi.platform.api.value.SettlementMoney.of(record.standardSalePrice(), CurrencyCode.CNY),
                com.yumi.platform.api.value.LossRate.of(record.glueLossRate()),
                cost(record.glueUnitPrice()),
                cost(record.colorPasteUnitPrice()),
                cost(record.makingCommission()),
                record.mouldCount(),
                record.mouldDailyBatches(),
                record.dailyCapacity(),
                record.status(),
                record.contentVersion(),
                record.rowVersion(),
                new ProductView.MakingProcessView(cost(costs.makingOtherMaterialCost())),
                new ProductView.BaggingProcessView(
                        costs.baggingStandardMinutes(),
                        cost(costs.baggingLaborCost()),
                        cost(costs.baggingBagUnitCost()),
                        MaterialSource.STUDIO,
                        cost(costs.baggingDecorationUnitPrice()),
                        MaterialSource.STUDIO,
                        cost(costs.baggingOtherCost())),
                new ProductView.SeamCuttingProcessView(
                        costs.seamStandardMinutes(),
                        cost(costs.seamLaborCost()),
                        cost(costs.seamCombinedMaterialCost()),
                        cost(costs.seamOtherCost())),
                new ProductView.ExtraUnitCostView(
                        cost(costs.extraOtherTaskLaborCost()),
                        cost(costs.extraTransportPackagingCost()),
                        cost(costs.extraDailyMiscCost()),
                        cost(costs.extraRentUtilitiesCost()),
                        cost(costs.extraMouldAmortizationCost()),
                        cost(costs.extraOtherCost())));
    }

    private static CostAmount cost(BigDecimal value) {
        return value == null
                ? CostAmount.of(BigDecimal.ZERO, CurrencyCode.CNY)
                : CostAmount.of(value, CurrencyCode.CNY);
    }

    /** 校验并规范化后的商品定义。 */
    private record ValidatedDefinition(
            String name,
            String description,
            BigDecimal standardWeightGrams,
            String makingStarCode,
            int makingStandardMinutes,
            BigDecimal standardSalePrice,
            BigDecimal glueLossRate,
            BigDecimal glueUnitPrice,
            BigDecimal colorPasteUnitPrice,
            BigDecimal makingCommission,
            int mouldCount,
            int mouldDailyBatches,
            BigDecimal makingOtherMaterialCost,
            int baggingStandardMinutes,
            BigDecimal baggingLaborCost,
            BigDecimal baggingBagUnitCost,
            BigDecimal baggingDecorationUnitPrice,
            BigDecimal baggingOtherCost,
            int seamStandardMinutes,
            BigDecimal seamLaborCost,
            BigDecimal seamCombinedMaterialCost,
            BigDecimal seamOtherCost,
            BigDecimal extraOtherTaskLaborCost,
            BigDecimal extraTransportPackagingCost,
            BigDecimal extraDailyMiscCost,
            BigDecimal extraRentUtilitiesCost,
            BigDecimal extraMouldAmortizationCost,
            BigDecimal extraOtherCost) {

        ProductRecord toRecord(
                String productBusinessId, String productNo, int dailyCapacity, ProductStatus status,
                Instant createdAt, String createdBy) {
            Instant now = createdAt;
            return new ProductRecord(
                    0L, productBusinessId, productNo, name, description, standardWeightGrams,
                    makingStarCode, makingStarCode, makingStandardMinutes, standardSalePrice,
                    glueLossRate, glueUnitPrice, colorPasteUnitPrice, makingCommission,
                    mouldCount, mouldDailyBatches, dailyCapacity, status,
                    1, 1, now, createdBy, now, createdBy,
                    new ProductRecord.ProcessCosts(
                            makingOtherMaterialCost, baggingStandardMinutes, baggingLaborCost,
                            baggingBagUnitCost, baggingDecorationUnitPrice, baggingOtherCost,
                            seamStandardMinutes, seamLaborCost, seamCombinedMaterialCost, seamOtherCost,
                            extraOtherTaskLaborCost, extraTransportPackagingCost, extraDailyMiscCost,
                            extraRentUtilitiesCost, extraMouldAmortizationCost, extraOtherCost));
        }
    }
}
