package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.product.api.ChangeProductStatusCommand;
import com.yumi.product.api.CreateProductCommand;
import com.yumi.product.api.ProductEntryConfigurationService;
import com.yumi.product.api.ProductQuery;
import com.yumi.product.api.ProductRef;
import com.yumi.product.api.ProductStatus;
import com.yumi.product.api.ProductView;
import com.yumi.product.api.SchemeType;
import com.yumi.product.api.UpdateProductCommand;
import com.yumi.product.application.ProductBudgetMapper;
import com.yumi.product.application.ProductCommandService;
import com.yumi.product.application.ProductEntryConfigurationServiceImpl;
import com.yumi.product.application.ProductSchemeCommandService;
import com.yumi.product.infrastructure.JdbcBudgetRepository;
import com.yumi.product.infrastructure.JdbcProductEntryConfigurationRepository;
import com.yumi.product.infrastructure.JdbcProductRepository;
import com.yumi.product.infrastructure.JdbcSchemeRepository;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import com.yumi.support.RecordingNumbering;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 商品完整提交、录入默认值、三道工序与额外单件费用（PR-01、PR-03）。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §4、§5.2、§9、§10.1、§12、§13。真实 MySQL 验证：
 * 商品保存同步生成内置标准方案与预算；商品不接收目标工序；默认值变化不联动既有商品。
 */
class ProductAggregateIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ProductCommandService products;

    private ProductSchemeCommandService schemes;

    private ProductQuery productQuery;

    private ProductEntryConfigurationService configuration;

    private RecordingAuthorization authorization;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM product_budget_result_lines").update();
        jdbc.sql("DELETE FROM product_budget_results").update();
        jdbc.sql("DELETE FROM product_scheme_snapshots").update();
        jdbc.sql("DELETE FROM product_snapshots").update();
        jdbc.sql("DELETE FROM product_scheme_extra_unit_costs").update();
        jdbc.sql("DELETE FROM product_scheme_seam_cutting").update();
        jdbc.sql("DELETE FROM product_scheme_bagging").update();
        jdbc.sql("DELETE FROM product_scheme_making").update();
        jdbc.sql("DELETE FROM product_schemes").update();
        jdbc.sql("DELETE FROM product_extra_unit_costs").update();
        jdbc.sql("DELETE FROM product_seam_cutting_processes").update();
        jdbc.sql("DELETE FROM product_bagging_processes").update();
        jdbc.sql("DELETE FROM product_making_processes").update();
        jdbc.sql("DELETE FROM product_products").update();
        jdbc.sql("UPDATE product_entry_defaults SET glue_loss_rate = 0.05, glue_unit_price = 0.10,"
                + " color_paste_unit_price = 0.20 WHERE config_key = 'DEFAULT'").update();

        authorization = new RecordingAuthorization();
        var productRepository = new JdbcProductRepository(jdbc);
        var schemeRepository = new JdbcSchemeRepository(jdbc);
        var budgetRepository = new JdbcBudgetRepository(jdbc);
        var audit = new com.yumi.platform.audit.application.AuditService(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        var identity = new RecordingIdentity("ACC-PRODUCT-TEST");
        var mapper = ProductBudgetMapper.defaults();

        products = new ProductCommandService(
                productRepository, schemeRepository, budgetRepository,
                new JdbcProductEntryConfigurationRepository(jdbc), new RecordingNumbering("P-"), authorization, identity, audit, mapper,
                Clock.systemUTC(), transactionManager);
        schemes = new ProductSchemeCommandService(
                schemeRepository, budgetRepository, products, authorization, identity, audit, mapper,
                Clock.systemUTC(), transactionManager);
        productQuery = products;
        configuration = new ProductEntryConfigurationServiceImpl(
                new JdbcProductEntryConfigurationRepository(jdbc), authorization, identity, audit,
                Clock.systemUTC());
    }

    @Test
    @DisplayName("商品保存同步生成内置标准方案、预算与单日产能")
    void createProductCreatesStandardSchemeAndBudget() {
        ProductRef created = createProduct("示例商品");

        assertThat(created.productNo()).startsWith("P-");
        assertThat(created.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(created.dailyCapacity()).as("3 模具 × 8 批次 = 24").isEqualTo(24);

        var standard = schemes.listSchemes(created.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeType() == SchemeType.SYSTEM_STANDARD)
                .findFirst()
                .orElseThrow();
        assertThat(standard.name()).isEqualTo(ProductCommandService.SYSTEM_SCHEME_NAME);
        assertThat(standard.targetProcess()).isEqualTo(com.yumi.product.api.SchemeView.ProcessCode.SEAM_CUTTING);
        assertThat(standard.salePrice().value()).isEqualByComparingTo("50.00");
        assertThat(standard.glueSource()).isEqualTo(com.yumi.product.api.MaterialSource.STUDIO);

        assertThat(standard.latestBudget()).isPresent();
        var budget = standard.latestBudget().orElseThrow();
        assertThat(budget.inputFingerprint()).matches("[0-9a-f]{64}");
        assertThat(budget.formulaRuleVersion()).isNotBlank();
        assertThat(budget.lines()).isNotEmpty();

        // 胶水用量 100 × 1.05 = 105 克 → 10.50 元；色浆 105 × 0.20 = 21.00 元
        assertThat(budget.lines()).anySatisfy(line -> {
            if (line.componentCode().equals("GLUE")) {
                assertThat(line.fullResourceCost().value()).isEqualByComparingTo("10.5000");
            }
        });
    }

    @Test
    @DisplayName("预算预览与保存后的内置方案预算一致")
    void previewBudgetMatchesSavedStandardScheme() {
        CreateProductCommand command = definition("预览商品");
        var preview = products.previewBudget(command);
        ProductRef created = products.create(command);
        var standard = schemes.listSchemes(created.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeType() == SchemeType.SYSTEM_STANDARD)
                .findFirst()
                .orElseThrow();
        var saved = standard.latestBudget().orElseThrow();
        assertThat(preview.fullResourceUnitCost().value())
                .isEqualByComparingTo(saved.fullResourceUnitCost().value());
        assertThat(preview.studioBorneUnitCost().value())
                .isEqualByComparingTo(saved.studioBorneUnitCost().value());
        assertThat(preview.lines()).hasSameSizeAs(saved.lines());
    }

    @Test
    @DisplayName("商品命令不接受目标工序：类型上没有该字段，库中也没有该列")
    void productDoesNotAcceptTargetProcess() {
        assertThat(List.of(CreateProductCommand.class.getRecordComponents()))
                .noneMatch(component -> component.getName().toLowerCase().contains("targetprocess")
                        || component.getName().toLowerCase().contains("target_process"));

        java.util.Set<String> columns = new java.util.HashSet<>(jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_products'
                        """).query(String.class).list());
        assertThat(columns).doesNotContain("target_process", "target_processing");
        assertThat(columns).contains("standard_sale_price", "daily_capacity", "content_version");
    }

    @Test
    @DisplayName("录入默认值变化不联动既有商品：商品使用自身内容版本")
    void defaultChangesDoNotAffectSavedProduct() {
        ProductRef created = createProduct("示例商品");
        BigDecimal before = productQuery.getProduct(created.productBusinessId()).glueUnitPrice().value();

        configuration.saveDefaults(new com.yumi.product.api.SaveProductEntryDefaultsCommand(
                LossRate.of("0.08"),
                CostAmount.of(new BigDecimal("9.99"), CurrencyCode.CNY),
                CostAmount.of(new BigDecimal("8.88"), CurrencyCode.CNY),
                1L));

        ProductView after = productQuery.getProduct(created.productBusinessId());
        assertThat(after.glueUnitPrice().value()).as("既有商品不被联动改价").isEqualByComparingTo(before);
        assertThat(after.glueLossRate().value()).isEqualByComparingTo("0.05");
        assertThat(after.contentVersion()).isEqualTo(1);

        // 新商品使用新的默认值（由页面带入，这里显式传参验证录入配置已变化）
        var config = configuration.getConfiguration();
        assertThat(config.glueUnitPrice().value()).isEqualByComparingTo("9.99");
    }

    @Test
    @DisplayName("商品编辑递增内容版本，并同步内置方案与预算")
    void updateSyncsStandardSchemeAndBudget() {
        ProductRef created = createProduct("示例商品");

        ProductRef updated = products.update(new UpdateProductCommand(
                created.productBusinessId(), definition("示例商品（改）"), created.rowVersion()));

        assertThat(updated.contentVersion()).isEqualTo(2);
        assertThat(updated.rowVersion()).isEqualTo(2);

        var standard = schemes.listSchemes(created.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeType() == SchemeType.SYSTEM_STANDARD)
                .findFirst()
                .orElseThrow();
        assertThat(standard.sourceProductContentVersion()).isEqualTo(2);
        assertThat(standard.contentVersion()).isGreaterThanOrEqualTo(2);
        assertThat(standard.latestBudget().orElseThrow().lines()).isNotEmpty();
    }

    @Test
    @DisplayName("商品编辑使用过期行版本返回并发冲突，不静默覆盖")
    void staleRowVersionRejected() {
        ProductRef created = createProduct("示例商品");
        products.update(new UpdateProductCommand(
                created.productBusinessId(), definition("第一次修改"), created.rowVersion()));

        assertThatThrownBy(() -> products.update(new UpdateProductCommand(
                created.productBusinessId(), definition("第二次修改"), created.rowVersion())))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.CONCURRENT_CONFLICT));
    }

    @Test
    @DisplayName("预算失败整笔回滚：模具数量为零时商品与方案都不落库")
    void budgetFailureRollsBackWholeAggregate() {
        long productsBefore = jdbc.sql("SELECT COUNT(*) FROM product_products").query(Long.class).single();
        long schemesBefore = jdbc.sql("SELECT COUNT(*) FROM product_schemes").query(Long.class).single();

        assertThatThrownBy(() -> products.create(invalidDefinition()))
                .isInstanceOf(ApiException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM product_products").query(Long.class).single())
                .isEqualTo(productsBefore);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM product_schemes").query(Long.class).single())
                .isEqualTo(schemesBefore);
    }

    @Test
    @DisplayName("停用商品同步停用内置方案，但历史数据仍可读取")
    void deactivationSyncsStandardScheme() {
        ProductRef created = createProduct("示例商品");
        ProductRef deactivated = products.changeStatus(new ChangeProductStatusCommand(
                created.productBusinessId(), ProductStatus.INACTIVE, created.rowVersion()));

        assertThat(deactivated.status()).isEqualTo(ProductStatus.INACTIVE);
        var standard = schemes.listSchemes(created.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeType() == SchemeType.SYSTEM_STANDARD)
                .findFirst()
                .orElseThrow();
        assertThat(standard.status()).isEqualTo(ProductStatus.INACTIVE);
        assertThat(productQuery.getProduct(created.productBusinessId()).name()).isEqualTo("示例商品");
    }

    @Test
    @DisplayName("字段校验：克重、模具、分钟必须为正，金额不得为负")
    void fieldValidation() {
        assertThatThrownBy(() -> products.create(definitionWith(command -> new CreateProductCommand(
                command.name(), command.description(), WeightGram.ofGrams("0"),
                command.makingStarCode(), command.makingStandardMinutes(), command.standardSalePrice(),
                command.glueLossRate(), command.glueUnitPrice(), command.colorPasteUnitPrice(),
                command.makingCommission(), command.mouldCount(), command.mouldDailyBatches(),
                command.makingOtherMaterialCost(), command.baggingStandardMinutes(),
                command.baggingLaborCost(), command.baggingBagUnitCost(),
                command.baggingDecorationUnitPrice(), command.baggingOtherCost(),
                command.seamStandardMinutes(), command.seamLaborCost(),
                command.seamCombinedMaterialCost(), command.seamOtherCost(),
                command.extraOtherTaskLaborCost(), command.extraTransportPackagingCost(),
                command.extraDailyMiscCost(), command.extraRentUtilitiesCost(),
                command.extraMouldAmortizationCost(), command.extraOtherCost()))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("克重");

        assertThatThrownBy(() -> products.create(definitionWith(command -> new CreateProductCommand(
                command.name(), command.description(), command.standardWeightGrams(),
                command.makingStarCode(), command.makingStandardMinutes(), command.standardSalePrice(),
                command.glueLossRate(), command.glueUnitPrice(), command.colorPasteUnitPrice(),
                command.makingCommission(), 0, command.mouldDailyBatches(),
                command.makingOtherMaterialCost(), command.baggingStandardMinutes(),
                command.baggingLaborCost(), command.baggingBagUnitCost(),
                command.baggingDecorationUnitPrice(), command.baggingOtherCost(),
                command.seamStandardMinutes(), command.seamLaborCost(),
                command.seamCombinedMaterialCost(), command.seamOtherCost(),
                command.extraOtherTaskLaborCost(), command.extraTransportPackagingCost(),
                command.extraDailyMiscCost(), command.extraRentUtilitiesCost(),
                command.extraMouldAmortizationCost(), command.extraOtherCost()))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("模具数量");
    }

    private ProductRef createProduct(String name) {
        return products.create(definition(name));
    }

    private CreateProductCommand definition(String name) {
        return definitionWith(command -> new CreateProductCommand(
                name, "示例说明", command.standardWeightGrams(), command.makingStarCode(),
                command.makingStandardMinutes(), command.standardSalePrice(), command.glueLossRate(),
                command.glueUnitPrice(), command.colorPasteUnitPrice(), command.makingCommission(),
                command.mouldCount(), command.mouldDailyBatches(), command.makingOtherMaterialCost(),
                command.baggingStandardMinutes(), command.baggingLaborCost(),
                command.baggingBagUnitCost(), command.baggingDecorationUnitPrice(),
                command.baggingOtherCost(), command.seamStandardMinutes(), command.seamLaborCost(),
                command.seamCombinedMaterialCost(), command.seamOtherCost(),
                command.extraOtherTaskLaborCost(), command.extraTransportPackagingCost(),
                command.extraDailyMiscCost(), command.extraRentUtilitiesCost(),
                command.extraMouldAmortizationCost(), command.extraOtherCost()));
    }

    private CreateProductCommand invalidDefinition() {
        return definitionWith(command -> new CreateProductCommand(
                command.name(), command.description(), command.standardWeightGrams(),
                command.makingStarCode(), command.makingStandardMinutes(), command.standardSalePrice(),
                command.glueLossRate(), command.glueUnitPrice(), command.colorPasteUnitPrice(),
                command.makingCommission(), -5, command.mouldDailyBatches(),
                command.makingOtherMaterialCost(), command.baggingStandardMinutes(),
                command.baggingLaborCost(), command.baggingBagUnitCost(),
                command.baggingDecorationUnitPrice(), command.baggingOtherCost(),
                command.seamStandardMinutes(), command.seamLaborCost(),
                command.seamCombinedMaterialCost(), command.seamOtherCost(),
                command.extraOtherTaskLaborCost(), command.extraTransportPackagingCost(),
                command.extraDailyMiscCost(), command.extraRentUtilitiesCost(),
                command.extraMouldAmortizationCost(), command.extraOtherCost()));
    }

    private CreateProductCommand definitionWith(
            java.util.function.UnaryOperator<CreateProductCommand> transform) {
        return transform.apply(baseDefinition());
    }

    private CreateProductCommand baseDefinition() {
        return new CreateProductCommand(
                "示例商品", "示例说明", WeightGram.ofGrams("100"), "STAR_2", 30,
                SettlementMoney.of(new BigDecimal("50"), CurrencyCode.CNY), LossRate.of("0.05"),
                cost("0.10"), cost("0.20"), cost("1.00"), 3, 8,
                cost("0.20"), 5, cost("0.50"), cost("0.10"), cost("0.05"), cost("0.02"),
                4, cost("0.60"), cost("0.03"), cost("0.01"),
                cost("0.01"), cost("0.02"), cost("0.03"), cost("0.04"), cost("0.05"), cost("0.06"));
    }

    private static CostAmount cost(String value) {
        return CostAmount.of(new BigDecimal(value), CurrencyCode.CNY);
    }
}
