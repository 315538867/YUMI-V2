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
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductEntryConfigurationService;
import com.yumi.product.api.ProductEntryConfigurationView;
import com.yumi.product.api.SaveMakingStarOptionCommand;
import com.yumi.product.api.SaveProcessTimeOptionCommand;
import com.yumi.product.api.SaveProductEntryDefaultsCommand;
import com.yumi.product.application.port.ProductEntryConfigurationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品录入配置服务（交付清单 D02）。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §9.1、§14：配置只用于录入时带出建议值；
 * 修改默认值不会刷新既有商品，也不生成商品快照。
 */
public class ProductEntryConfigurationServiceImpl implements ProductEntryConfigurationService {

    private final ProductEntryConfigurationRepository repository;

    private final AuthorizationService authorization;

    private final CurrentActorProvider currentActor;

    private final AuditService audit;

    private final Clock clock;

    public ProductEntryConfigurationServiceImpl(
            ProductEntryConfigurationRepository repository,
            AuthorizationService authorization,
            CurrentActorProvider currentActor,
            AuditService audit,
            Clock clock) {
        this.repository = repository;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public ProductEntryConfigurationView getConfiguration() {
        authorization.require(PermissionCodes.PRODUCT_READ, ResourceScope.GLOBAL);
        ProductEntryConfigurationRepository.DefaultsRecord defaults = repository.loadDefaults();
        return new ProductEntryConfigurationView(
                com.yumi.platform.api.value.LossRate.of(defaults.glueLossRate()),
                cost(defaults.glueUnitPrice()),
                cost(defaults.colorPasteUnitPrice()),
                defaults.rowVersion(),
                repository.loadMakingStars().stream()
                        .filter(star -> "ACTIVE".equals(star.status()))
                        .map(star -> new ProductEntryConfigurationView.StarOption(
                                star.starCode(), star.name(), star.standardMinutes(), star.sortOrder()))
                        .toList(),
                repository.loadProcessTimeOptions(null).stream()
                        .filter(option -> "ACTIVE".equals(option.status()))
                        .map(option -> new ProductEntryConfigurationView.ProcessTimeOption(
                                option.optionBusinessId(), option.processCode(), option.optionCode(),
                                option.name(), option.standardMinutes(), option.sortOrder()))
                        .toList());
    }

    @Override
    @Transactional
    public void saveDefaults(SaveProductEntryDefaultsCommand command) {
        authorization.require(PermissionCodes.PRODUCT_UPDATE, ResourceScope.GLOBAL);
        if (command == null || command.glueLossRate() == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_FIELD", "胶水损耗率不能为空");
        }
        requireNonNegative(command.glueUnitPrice(), "胶水单价");
        requireNonNegative(command.colorPasteUnitPrice(), "色浆单价");

        Instant now = clock.instant();
        String operatorId = currentActor.requireCurrentActor().accountBusinessId().value();
        if (!repository.updateDefaults(
                command.glueLossRate().value(), command.glueUnitPrice().value(),
                command.colorPasteUnitPrice().value(), command.expectedRowVersion(), now, operatorId)) {
            throw new ApiException(
                    ApiErrorType.CONCURRENT_CONFLICT, "PRODUCT_VERSION_CONFLICT",
                    "录入默认值已被其他操作修改，请重新打开后再保存");
        }
        recordAudit("UPDATE_ENTRY_DEFAULTS", "DEFAULT", Map.of("glueLossRate",
                command.glueLossRate().value().toPlainString()));
    }

    @Override
    @Transactional
    public void saveMakingStar(SaveMakingStarOptionCommand command) {
        authorization.require(PermissionCodes.PRODUCT_UPDATE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_FIELD", "星级选项不能为空");
        }
        String starCode = ProductCommandService.requireText(command.starCode(), "星级代码", 32);
        String name = ProductCommandService.requireText(command.name(), "星级名称", 64);
        if (command.standardMinutes() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "星级标准分钟必须大于 0");
        }
        repository.upsertMakingStar(
                new ProductEntryConfigurationRepository.StarRecord(
                        starCode, name, command.standardMinutes(), command.sortOrder(),
                        command.active() ? "ACTIVE" : "INACTIVE", command.expectedRowVersion()),
                clock.instant(),
                currentActor.requireCurrentActor().accountBusinessId().value());
        recordAudit("UPSERT_MAKING_STAR", starCode, Map.of("name", name));
    }

    @Override
    @Transactional
    public void saveProcessTimeOption(SaveProcessTimeOptionCommand command) {
        authorization.require(PermissionCodes.PRODUCT_UPDATE, ResourceScope.GLOBAL);
        if (command == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_FIELD", "工序时间选项不能为空");
        }
        String processCode = ProductCommandService.requireText(command.processCode(), "工序代码", 32);
        if (!List.of("BAGGING", "SEAM_CUTTING").contains(processCode)) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID",
                    "只有捏毛装袋与缝边剪袋支持工序时间选项");
        }
        if (command.standardMinutes() <= 0) {
            throw new ApiException(
                    ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", "工序标准分钟必须大于 0");
        }
        String optionBusinessId = command.optionBusinessId() == null || command.optionBusinessId().isBlank()
                ? "PTO-" + java.util.UUID.randomUUID()
                : command.optionBusinessId();
        repository.upsertProcessTimeOption(
                new ProductEntryConfigurationRepository.ProcessTimeRecord(
                        optionBusinessId, processCode,
                        ProductCommandService.requireText(command.optionCode(), "选项代码", 32),
                        ProductCommandService.requireText(command.name(), "选项名称", 64),
                        command.standardMinutes(), command.sortOrder(),
                        command.active() ? "ACTIVE" : "INACTIVE", command.expectedRowVersion()),
                clock.instant(),
                currentActor.requireCurrentActor().accountBusinessId().value());
        recordAudit("UPSERT_PROCESS_TIME_OPTION", optionBusinessId, Map.of("processCode", processCode));
    }

    private void recordAudit(String actionCode, String targetBusinessId, Map<String, String> detail) {
        audit.record(new RecordAuditCommand(
                RequestCorrelationHolder.getOrUnknown(),
                currentActor.requireCurrentActor().accountBusinessId().value(),
                "product",
                actionCode,
                "PRODUCT_CONFIG",
                targetBusinessId,
                "SUCCESS",
                new LinkedHashMap<>(detail),
                clock.instant()));
    }

    private static void requireNonNegative(CostAmount amount, String label) {
        if (amount == null) {
            throw new ApiException(ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", label + "不能为空");
        }
        if (amount.value().signum() < 0) {
            throw new ApiException(ApiErrorType.VALIDATION, "PRODUCT_FIELD_INVALID", label + "不允许为负");
        }
    }

    private static CostAmount cost(BigDecimal value) {
        return value == null
                ? CostAmount.of(BigDecimal.ZERO, CurrencyCode.CNY)
                : CostAmount.of(value, CurrencyCode.CNY);
    }

    /** 未使用的导入占位，保持与 MaterialSource 的显式依赖便于后续扩展。 */
    @SuppressWarnings("unused")
    private static MaterialSource studio() {
        return MaterialSource.STUDIO;
    }
}
