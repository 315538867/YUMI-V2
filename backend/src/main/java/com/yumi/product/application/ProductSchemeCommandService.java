package com.yumi.product.application;

import com.yumi.platform.api.audit.AuditService;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.identity.CurrentActorProvider;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.http.RequestCorrelationHolder;
import com.yumi.product.api.CreateCustomSchemeCommand;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductSchemeApplicationService;
import com.yumi.product.api.ProductStatus;
import com.yumi.product.api.SchemeRef;
import com.yumi.product.api.SchemeType;
import com.yumi.product.api.SchemeView;
import com.yumi.product.api.UpdateCustomSchemeCommand;
import com.yumi.product.application.port.BudgetRepository;
import com.yumi.product.application.port.ProductRecord;
import com.yumi.product.application.port.SchemeRecord;
import com.yumi.product.application.port.SchemeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 方案应用服务。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §5、§12、§13：
 * <ul>
 *   <li>内置标准方案由商品维护，任何独立编辑或停用都拒绝；</li>
 *   <li>自定义方案只允许覆盖非人工费用与供料方；人工、工时、提成与额外单件费用来自商品；</li>
 *   <li>提交时重新校验商品内容版本，避免基于过时继承值提交；</li>
 *   <li>方案与预算结果同事务提交，预算失败整笔回滚。</li>
 * </ul>
 */
public class ProductSchemeCommandService implements ProductSchemeApplicationService {

    private final SchemeRepository schemes;

    private final BudgetRepository budgets;

    private final ProductCommandService products;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final AuditService audit;

    private final ProductBudgetMapper budgetMapper;

    private final Clock clock;

    private final TransactionTemplate transactionTemplate;

    public ProductSchemeCommandService(
            SchemeRepository schemes,
            BudgetRepository budgets,
            ProductCommandService products,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            ProductBudgetMapper budgetMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.schemes = schemes;
        this.budgets = budgets;
        this.products = products;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.budgetMapper = budgetMapper;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public SchemeRef createCustomScheme(CreateCustomSchemeCommand command) {
        authorization.require(PermissionCodes.PRODUCT_SCHEME_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_SCHEME", "方案定义不能为空");
        }
        String productBusinessId = ProductCommandService.requireText(
                command.productBusinessId(), "商品标识", 64);
        String name = ProductCommandService.requireText(command.name(), "方案名称", 80);
        requireTargetProcess(command.targetProcess());

        return transactionTemplate.execute(status -> {
            ProductRecord product = products.requireProduct(productBusinessId);
            products.requireActiveProduct(product);
            requireCurrentProductVersion(product, command.expectedProductContentVersion());

            Instant now = clock.instant();
            SchemeRecord scheme = new SchemeRecord(
                    0L,
                    "SCH-" + UUID.randomUUID(),
                    productBusinessId,
                    SchemeType.CUSTOM,
                    name,
                    product.standardWeightGrams(),
                    command.targetProcess().name(),
                    command.salePrice().value(),
                    requireSource(command.glueSource()).name(),
                    requireSource(command.colorPasteSource()).name(),
                    ProductStatus.ACTIVE,
                    product.contentVersion(),
                    1,
                    1,
                    now,
                    now,
                    mergeCosts(product, null, command));
            schemes.insert(scheme);
            products.recalculateBudget(product, scheme, now);
            recordAudit("CREATE_CUSTOM_SCHEME", scheme.schemeBusinessId(),
                    Map.of("productBusinessId", productBusinessId, "targetProcess",
                            command.targetProcess().name()));
            return toRef(scheme);
        });
    }

    @Override
    public SchemeView.SchemeBudgetView previewBudget(
            String productBusinessId, CreateCustomSchemeCommand command) {
        authorization.require(PermissionCodes.PRODUCT_SCHEME_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_SCHEME", "方案定义不能为空");
        }
        requireTargetProcess(command.targetProcess());
        ProductRecord product = products.requireProduct(
                ProductCommandService.requireText(productBusinessId, "商品标识", 64));
        products.requireActiveProduct(product);
        Instant now = clock.instant();
        SchemeRecord scheme = new SchemeRecord(
                0L,
                "SCHEME-PREVIEW",
                product.productBusinessId(),
                SchemeType.CUSTOM,
                command.name() == null || command.name().isBlank()
                        ? "预览方案" : command.name().strip(),
                product.standardWeightGrams(),
                command.targetProcess().name(),
                command.salePrice().value(),
                requireSource(command.glueSource()).name(),
                requireSource(command.colorPasteSource()).name(),
                ProductStatus.ACTIVE,
                product.contentVersion(),
                1,
                1,
                now,
                now,
                mergeCosts(product, null, command));
        var result = budgetMapper.calculateBudget(product, scheme);
        return ProductBudgetMapper.toView(budgetMapper.toRecord(
                "BUDGET-PREVIEW", product.contentVersion(), 1, result, "SCHEME-PREVIEW"));
    }

    @Override
    public SchemeRef updateCustomScheme(UpdateCustomSchemeCommand command) {
        authorization.require(PermissionCodes.PRODUCT_SCHEME_MANAGE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_SCHEME", "方案定义不能为空");
        }
        String schemeBusinessId = ProductCommandService.requireText(
                command.schemeBusinessId(), "方案标识", 64);
        String name = ProductCommandService.requireText(command.name(), "方案名称", 80);
        requireTargetProcess(command.targetProcess());

        return transactionTemplate.execute(status -> {
            SchemeRecord existing = schemes.findByBusinessId(schemeBusinessId)
                    .orElseThrow(() -> new ApiException(
                            ApiErrorType.NOT_FOUND, "PRODUCT_SCHEME_INVALID", "方案不存在"));
            if (existing.schemeType() == SchemeType.SYSTEM_STANDARD) {
                throw new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCT_SCHEME_SYSTEM_MANAGED",
                        "内置标准方案由商品维护，不能独立编辑");
            }

            ProductRecord product = products.requireProduct(existing.productBusinessId());
            requireCurrentProductVersion(product, command.expectedProductContentVersion());

            Instant now = clock.instant();
            SchemeRecord updated = new SchemeRecord(
                    existing.id(),
                    existing.schemeBusinessId(),
                    existing.productBusinessId(),
                    SchemeType.CUSTOM,
                    name,
                    product.standardWeightGrams(),
                    command.targetProcess().name(),
                    command.salePrice().value(),
                    requireSource(command.glueSource()).name(),
                    requireSource(command.colorPasteSource()).name(),
                    existing.status(),
                    product.contentVersion(),
                    existing.contentVersion(),
                    existing.rowVersion(),
                    existing.createdAt(),
                    now,
                    mergeCosts(product, existing, command));
            if (!schemes.update(updated, command.expectedSchemeRowVersion(), now)) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_VERSION_CONFLICT",
                        "方案已被其他操作修改，请重新打开后再保存");
            }

            SchemeRecord reloaded = schemes.findByBusinessId(schemeBusinessId).orElseThrow();
            products.recalculateBudget(product, reloaded, now);
            recordAudit("UPDATE_CUSTOM_SCHEME", schemeBusinessId,
                    Map.of("targetProcess", command.targetProcess().name()));
            return toRef(reloaded);
        });
    }

    @Override
    public void deactivateCustomScheme(String schemeBusinessId, long expectedRowVersion) {
        authorization.require(PermissionCodes.PRODUCT_SCHEME_MANAGE, ResourceScope.GLOBAL);
        SchemeRecord existing = schemes.findByBusinessId(
                        ProductCommandService.requireText(schemeBusinessId, "方案标识", 64))
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_SCHEME_INVALID", "方案不存在"));
        if (existing.schemeType() == SchemeType.SYSTEM_STANDARD) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCT_SCHEME_SYSTEM_MANAGED",
                    "内置标准方案随商品状态，不能单独停用");
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (!schemes.updateStatus(
                    schemeBusinessId, ProductStatus.INACTIVE.name(), expectedRowVersion, clock.instant())) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_VERSION_CONFLICT",
                        "方案已被其他操作修改，请重新打开后再操作");
            }
            recordAudit("DEACTIVATE_CUSTOM_SCHEME", schemeBusinessId, Map.of());
        });
    }

    @Override
    public void activateCustomScheme(String schemeBusinessId, long expectedRowVersion) {
        authorization.require(PermissionCodes.PRODUCT_SCHEME_MANAGE, ResourceScope.GLOBAL);
        SchemeRecord existing = schemes.findByBusinessId(
                        ProductCommandService.requireText(schemeBusinessId, "方案标识", 64))
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_SCHEME_INVALID", "方案不存在"));
        if (existing.schemeType() == SchemeType.SYSTEM_STANDARD) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCT_SCHEME_SYSTEM_MANAGED",
                    "内置标准方案随商品状态，不能单独启用");
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (!schemes.updateStatus(
                    schemeBusinessId, ProductStatus.ACTIVE.name(), expectedRowVersion, clock.instant())) {
                throw new ApiException(
                        ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_VERSION_CONFLICT",
                        "方案已被其他操作修改，请重新打开后再操作");
            }
            recordAudit("ACTIVATE_CUSTOM_SCHEME", schemeBusinessId, Map.of());
        });
    }

    @Override
    public List<SchemeView> listSchemes(String productBusinessId) {
        authorization.require(PermissionCodes.PRODUCT_READ, ResourceScope.GLOBAL);
        return schemes.findAllOfProduct(
                        ProductCommandService.requireText(productBusinessId, "商品标识", 64)).stream()
                .map(scheme -> toView(scheme, budgets.findByScheme(scheme.schemeBusinessId())))
                .toList();
    }

    /** 供快照与生产依据复用：读取方案并校验存在。 */
    public SchemeRecord requireScheme(String schemeBusinessId) {
        return schemes.findByBusinessId(schemeBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_SCHEME_INVALID", "方案不存在"));
    }

    /** 供快照与生产依据复用：读取内置标准方案。 */
    public SchemeRecord requireStandardScheme(String productBusinessId) {
        return schemes.findStandardOfProduct(productBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_SCHEME_INVALID", "商品缺少内置标准方案"));
    }

    /** 供生产依据复用：读取方案当前预算。 */
    public BudgetRepository.BudgetRecord requireBudget(String schemeBusinessId) {
        return budgets.findByScheme(schemeBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "PRODUCT_BUDGET_CALCULATION_FAILED",
                        "方案缺少当前预算结果"));
    }

    private static void requireCurrentProductVersion(ProductRecord product, long expectedProductContentVersion) {
        if (product.contentVersion() != expectedProductContentVersion) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_VERSION_CONFLICT",
                    "商品内容已变化，请重新打开方案后再保存");
        }
    }

    private static void requireTargetProcess(SchemeView.ProcessCode targetProcess) {
        if (targetProcess == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_TARGET_PROCESS_INVALID", "目标工序不能为空");
        }
    }

    private static MaterialSource requireSource(MaterialSource source) {
        if (source == null) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_SCHEME_INVALID", "供料方不能为空（必须明确选择）");
        }
        return source;
    }

    /**
     * 合并方案字段：继承字段取商品，非人工覆盖字段取命令。
     *
     * <p>人工、工时、提成与额外单件费用<b>只</b>来自商品；命令里没有这些字段，从类型上就无法越界。
     */
    private static SchemeRecord.SchemeCosts mergeCosts(
            ProductRecord product, SchemeRecord existing, Object command) {
        ProductRecord.ProcessCosts productCosts = product.processCosts();
        BigDecimal makingOther;
        BigDecimal bagUnit;
        String bagSource;
        BigDecimal decoration;
        String decorationSource;
        BigDecimal baggingOther;
        BigDecimal seamMaterial;
        BigDecimal seamOther;

        if (command instanceof CreateCustomSchemeCommand create) {
            makingOther = create.makingOtherMaterialCost().value();
            bagUnit = create.baggingBagUnitCost().value();
            bagSource = requireSource(create.baggingBagSource()).name();
            decoration = create.baggingDecorationUnitPrice().value();
            decorationSource = requireSource(create.baggingDecorationSource()).name();
            baggingOther = create.baggingOtherCost().value();
            seamMaterial = create.seamCombinedMaterialCost().value();
            seamOther = create.seamOtherCost().value();
        } else {
            UpdateCustomSchemeCommand update = (UpdateCustomSchemeCommand) command;
            makingOther = update.makingOtherMaterialCost().value();
            bagUnit = update.baggingBagUnitCost().value();
            bagSource = requireSource(update.baggingBagSource()).name();
            decoration = update.baggingDecorationUnitPrice().value();
            decorationSource = requireSource(update.baggingDecorationSource()).name();
            baggingOther = update.baggingOtherCost().value();
            seamMaterial = update.seamCombinedMaterialCost().value();
            seamOther = update.seamOtherCost().value();
        }

        return new SchemeRecord.SchemeCosts(
                product.makingStandardMinutes(),
                product.makingCommission(),
                makingOther,
                productCosts.baggingStandardMinutes(),
                productCosts.baggingLaborCost(),
                bagUnit,
                bagSource,
                decoration,
                decorationSource,
                baggingOther,
                productCosts.seamStandardMinutes(),
                productCosts.seamLaborCost(),
                seamMaterial,
                seamOther,
                productCosts.extraOtherTaskLaborCost(),
                productCosts.extraTransportPackagingCost(),
                productCosts.extraDailyMiscCost(),
                productCosts.extraRentUtilitiesCost(),
                productCosts.extraMouldAmortizationCost(),
                productCosts.extraOtherCost());
    }

    private void recordAudit(String actionCode, String targetBusinessId, Map<String, String> detail) {
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(),
                currentActor.requireCurrentActor().accountBusinessId().value(),
                "product",
                actionCode,
                "SCHEME",
                targetBusinessId,
                "SUCCESS",
                new LinkedHashMap<>(detail),
                clock.instant()));
    }

    private static SchemeRef toRef(SchemeRecord record) {
        return new SchemeRef(
                record.schemeBusinessId(), record.productBusinessId(), record.schemeType(),
                record.contentVersion(), record.rowVersion());
    }

    /** 方案视图：内置方案与自定义方案结构一致，继承字段明确标注只读。 */
    public static SchemeView toView(
            SchemeRecord record, java.util.Optional<BudgetRepository.BudgetRecord> budget) {
        SchemeRecord.SchemeCosts costs = record.costs();
        return new SchemeView(
                record.schemeBusinessId(),
                record.productBusinessId(),
                record.schemeType(),
                record.name(),
                com.yumi.platform.api.value.WeightGram.ofGrams(record.standardWeightGrams()),
                SchemeView.ProcessCode.valueOf(record.targetProcess()),
                com.yumi.platform.api.value.SettlementMoney.of(record.salePrice(), CurrencyCode.CNY),
                MaterialSource.valueOf(record.glueSource()),
                MaterialSource.valueOf(record.colorPasteSource()),
                record.status(),
                record.sourceProductContentVersion(),
                record.contentVersion(),
                record.rowVersion(),
                new SchemeView.MakingView(
                        costs.makingStandardMinutes(),
                        cost(costs.makingCommission()),
                        cost(costs.makingOtherMaterialCost())),
                new SchemeView.BaggingView(
                        costs.baggingStandardMinutes(),
                        cost(costs.baggingLaborCost()),
                        cost(costs.baggingBagUnitCost()),
                        MaterialSource.valueOf(costs.baggingBagSource()),
                        cost(costs.baggingDecorationUnitPrice()),
                        MaterialSource.valueOf(costs.baggingDecorationSource()),
                        cost(costs.baggingOtherCost())),
                new SchemeView.SeamCuttingView(
                        costs.seamStandardMinutes(),
                        cost(costs.seamLaborCost()),
                        cost(costs.seamCombinedMaterialCost()),
                        cost(costs.seamOtherCost())),
                new com.yumi.product.api.ProductView.ExtraUnitCostView(
                        cost(costs.extraOtherTaskLaborCost()),
                        cost(costs.extraTransportPackagingCost()),
                        cost(costs.extraDailyMiscCost()),
                        cost(costs.extraRentUtilitiesCost()),
                        cost(costs.extraMouldAmortizationCost()),
                        cost(costs.extraOtherCost())),
                budget.map(ProductBudgetMapper::toView));
    }

    private static CostAmount cost(BigDecimal value) {
        return value == null
                ? CostAmount.of(BigDecimal.ZERO, CurrencyCode.CNY)
                : CostAmount.of(value, CurrencyCode.CNY);
    }

    /** 未使用的占位，保留显式 UUID 依赖以便审计关联。 */
    @SuppressWarnings("unused")
    private static String newId() {
        return "SCH-" + UUID.randomUUID();
    }
}
