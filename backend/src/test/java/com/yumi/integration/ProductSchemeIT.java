package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import com.yumi.product.api.CreateCustomSchemeCommand;
import com.yumi.product.api.CreateProductCommand;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductRef;
import com.yumi.product.api.SchemeType;
import com.yumi.product.api.SchemeView;
import com.yumi.product.api.UpdateCustomSchemeCommand;
import com.yumi.product.application.ProductBudgetMapper;
import com.yumi.product.application.ProductCommandService;
import com.yumi.product.application.ProductSchemeCommandService;
import com.yumi.product.infrastructure.JdbcBudgetRepository;
import com.yumi.product.infrastructure.JdbcProductRepository;
import com.yumi.product.infrastructure.JdbcSchemeRepository;
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
 * 固定三工序标准方案与允许短流程的自定义方案（PR-02）。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §5.2、§5.3、§13。真实 MySQL 验证：
 * 内置方案不可独立编辑；自定义方案只能改非人工字段；短流程只改方案范围，不改商品标准方案。
 */
class ProductSchemeIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ProductCommandService products;

    private ProductSchemeCommandService schemes;

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

        var authorization = new RecordingAuthorization();
        var productRepository = new JdbcProductRepository(jdbc);
        var schemeRepository = new JdbcSchemeRepository(jdbc);
        var budgetRepository = new JdbcBudgetRepository(jdbc);
        var audit = new com.yumi.platform.audit.application.AuditService(
                jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        var identity = new RecordingIdentity("ACC-PRODUCT-TEST");
        var mapper = ProductBudgetMapper.defaults();

        products = new ProductCommandService(
                productRepository, schemeRepository, budgetRepository,
                new RecordingNumbering("P-"), authorization, identity, audit, mapper,
                Clock.systemUTC(), transactionManager);
        schemes = new ProductSchemeCommandService(
                schemeRepository, budgetRepository, products, authorization, identity, audit, mapper,
                Clock.systemUTC(), transactionManager);
    }

    @Test
    @DisplayName("内置标准方案固定覆盖三道工序，且不可独立编辑或停用")
    void systemSchemeIsFixedAndManaged() {
        ProductRef product = createProduct();
        SchemeView standard = standardScheme(product);

        assertThat(standard.targetProcess()).isEqualTo(SchemeView.ProcessCode.SEAM_CUTTING);
        assertThat(standard.schemeType()).isEqualTo(SchemeType.SYSTEM_STANDARD);

        assertThatThrownBy(() -> schemes.updateCustomScheme(new UpdateCustomSchemeCommand(
                standard.schemeBusinessId(), "改名", SchemeView.ProcessCode.MAKING,
                SettlementMoney.of(new BigDecimal("99"), CurrencyCode.CNY),
                MaterialSource.STUDIO, MaterialSource.STUDIO,
                cost("0.20"), cost("0.10"), MaterialSource.STUDIO, cost("0.05"),
                MaterialSource.STUDIO, cost("0.02"), cost("0.03"), cost("0.01"),
                standard.sourceProductContentVersion(), standard.rowVersion())))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCT_SCHEME_SYSTEM_MANAGED"));

        assertThatThrownBy(() -> schemes.deactivateCustomScheme(
                standard.schemeBusinessId(), standard.rowVersion()))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCT_SCHEME_SYSTEM_MANAGED"));
    }

    @Test
    @DisplayName("自定义方案短流程只计算适用工序，不改商品标准方案范围")
    void shortFlowOnlyAffectsCustomScheme() {
        ProductRef product = createProduct();
        SchemeView makingOnly = createCustomScheme(product, SchemeView.ProcessCode.MAKING);

        assertThat(makingOnly.targetProcess()).isEqualTo(SchemeView.ProcessCode.MAKING);
        var budget = makingOnly.latestBudget().orElseThrow();
        assertThat(budget.lines())
                .extracting(SchemeView.BudgetLineView::componentCode)
                .contains("MAKING_LABOR", "GLUE", "COLOR_PASTE")
                .doesNotContain("BAGGING_LABOR", "SEAM_LABOR");

        assertThat(standardScheme(product).targetProcess())
                .as("短流程不修改内置标准方案范围")
                .isEqualTo(SchemeView.ProcessCode.SEAM_CUTTING);
    }

    @Test
    @DisplayName("自定义方案继承人工与工时，只允许覆盖非人工费用与供料方")
    void customSchemeInheritsLaborAndAllowsOnlyNonLaborOverrides() {
        ProductRef product = createProduct();
        SchemeView custom = createCustomScheme(product, SchemeView.ProcessCode.SEAM_CUTTING);

        // 继承字段来自商品
        assertThat(custom.making().makingCommission().value()).isEqualByComparingTo("1.00");
        assertThat(custom.making().standardMinutes()).isEqualTo(30);
        assertThat(custom.bagging().standardMinutes()).isEqualTo(5);
        assertThat(custom.seamCutting().standardMinutes()).isEqualTo(4);

        // 非人工字段可覆盖
        assertThat(custom.bagging().bagUnitCost().value()).isEqualByComparingTo("0.25");

        // 命令类型上不存在人工、工时、提成与额外费用字段
        assertThat(List.of(CreateCustomSchemeCommand.class.getRecordComponents()))
                .noneMatch(component -> component.getName().toLowerCase().contains("labor")
                        || component.getName().toLowerCase().contains("commission")
                        || component.getName().toLowerCase().contains("standardminutes")
                        || component.getName().toLowerCase().contains("extracost"));
    }

    @Test
    @DisplayName("客户供料保留标准价值，但不进入工作室承担成本")
    void customerSuppliedMaterialKeepsValueButNotStudioCost() {
        ProductRef product = createProduct();
        var createdRef = schemes.createCustomScheme(new CreateCustomSchemeCommand(
                product.productBusinessId(), "客户供胶水", SchemeView.ProcessCode.MAKING,
                SettlementMoney.of(new BigDecimal("50"), CurrencyCode.CNY),
                MaterialSource.CUSTOMER, MaterialSource.STUDIO,
                cost("0.20"), cost("0.10"), MaterialSource.STUDIO, cost("0.05"),
                MaterialSource.STUDIO, cost("0.02"), cost("0.03"), cost("0.01"),
                1L));
        SchemeView custom = schemes.listSchemes(product.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeBusinessId().equals(createdRef.schemeBusinessId()))
                .findFirst()
                .orElseThrow();

        var budget = custom.latestBudget().orElseThrow();
        assertThat(budget.customerSuppliedUnitValue().value()).isEqualByComparingTo("10.5000");
        assertThat(budget.studioBorneUnitCost().value())
                .isEqualByComparingTo(budget.fullResourceUnitCost().value().subtract(new BigDecimal("10.5000")));
    }

    @Test
    @DisplayName("提交时商品内容版本已变化则拒绝，避免基于过时继承值提交")
    void staleProductVersionIsRejected() {
        ProductRef product = createProduct();
        products.update(new com.yumi.product.api.UpdateProductCommand(
                product.productBusinessId(), definition("示例商品（改）"), product.rowVersion()));

        assertThatThrownBy(() -> schemes.createCustomScheme(new CreateCustomSchemeCommand(
                product.productBusinessId(), "过时方案", SchemeView.ProcessCode.MAKING,
                SettlementMoney.of(new BigDecimal("50"), CurrencyCode.CNY),
                MaterialSource.STUDIO, MaterialSource.STUDIO,
                cost("0.20"), cost("0.10"), MaterialSource.STUDIO, cost("0.05"),
                MaterialSource.STUDIO, cost("0.02"), cost("0.03"), cost("0.01"),
                1L)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.CONCURRENT_CONFLICT));
    }

    @Test
    @DisplayName("商品字段变化时自定义方案的继承字段同步更新，内容版本递增")
    void productChangeSyncsInheritedValuesIntoCustomSchemes() {
        ProductRef product = createProduct();
        SchemeView custom = createCustomScheme(product, SchemeView.ProcessCode.MAKING);

        products.update(new com.yumi.product.api.UpdateProductCommand(
                product.productBusinessId(), definition("示例商品（提成变更）", "2.50"), product.rowVersion()));

        SchemeView reloaded = schemes.listSchemes(product.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeBusinessId().equals(custom.schemeBusinessId()))
                .findFirst()
                .orElseThrow();

        assertThat(reloaded.making().makingCommission().value())
                .as("提成由商品同步，方案不可自定义")
                .isEqualByComparingTo("2.50");
        assertThat(reloaded.contentVersion()).isGreaterThan(custom.contentVersion());
        assertThat(reloaded.latestBudget().orElseThrow().lines()).isNotEmpty();
    }

    @Test
    @DisplayName("停用商品后不能再创建自定义方案")
    void inactiveProductRejectsNewScheme() {
        ProductRef product = createProduct();
        products.changeStatus(new com.yumi.product.api.ChangeProductStatusCommand(
                product.productBusinessId(), com.yumi.product.api.ProductStatus.INACTIVE, product.rowVersion()));

        assertThatThrownBy(() -> createCustomScheme(product, SchemeView.ProcessCode.MAKING))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCT_INACTIVE"));
    }

    @Test
    @DisplayName("方案列表同时返回内置与自定义方案，内置在前")
    void listReturnsSystemThenCustom() {
        ProductRef product = createProduct();
        createCustomScheme(product, SchemeView.ProcessCode.BAGGING);

        List<SchemeView> all = schemes.listSchemes(product.productBusinessId());
        assertThat(all).hasSize(2);
        assertThat(all.getFirst().schemeType()).isEqualTo(SchemeType.SYSTEM_STANDARD);
        assertThat(all.get(1).schemeType()).isEqualTo(SchemeType.CUSTOM);
        assertThat(all.get(1).targetProcess()).isEqualTo(SchemeView.ProcessCode.BAGGING);
    }

    @Test
    @DisplayName("自定义方案预算预览与保存结果一致")
    void previewBudgetMatchesSavedCustomScheme() {
        ProductRef product = createProduct();
        CreateCustomSchemeCommand command = new CreateCustomSchemeCommand(
                product.productBusinessId(), "预览方案", SchemeView.ProcessCode.SEAM_CUTTING,
                SettlementMoney.of(new BigDecimal("55"), CurrencyCode.CNY),
                MaterialSource.STUDIO, MaterialSource.STUDIO,
                cost("0.20"), cost("0.25"), MaterialSource.STUDIO, cost("0.05"),
                MaterialSource.STUDIO, cost("0.02"), cost("0.03"), cost("0.01"), 1L);

        var preview = schemes.previewBudget(product.productBusinessId(), command);
        var saved = createCustomScheme(product, SchemeView.ProcessCode.SEAM_CUTTING).latestBudget()
                .orElseThrow();

        assertThat(preview.fullResourceUnitCost().value())
                .isEqualByComparingTo(saved.fullResourceUnitCost().value());
        assertThat(preview.studioBorneUnitCost().value())
                .isEqualByComparingTo(saved.studioBorneUnitCost().value());
        assertThat(preview.lines()).hasSameSizeAs(saved.lines());
    }

    private SchemeView createCustomScheme(ProductRef product, SchemeView.ProcessCode targetProcess) {
        var ref = schemes.createCustomScheme(new CreateCustomSchemeCommand(
                product.productBusinessId(), "自定义方案", targetProcess,
                SettlementMoney.of(new BigDecimal("55"), CurrencyCode.CNY),
                MaterialSource.STUDIO, MaterialSource.STUDIO,
                cost("0.20"), cost("0.25"), MaterialSource.STUDIO, cost("0.05"),
                MaterialSource.STUDIO, cost("0.02"), cost("0.03"), cost("0.01"),
                1L));
        return schemes.listSchemes(product.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeBusinessId().equals(ref.schemeBusinessId()))
                .findFirst()
                .orElseThrow();
    }

    private SchemeView standardScheme(ProductRef product) {
        return schemes.listSchemes(product.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeType() == SchemeType.SYSTEM_STANDARD)
                .findFirst()
                .orElseThrow();
    }

    private ProductRef createProduct() {
        return products.create(definition("示例商品"));
    }

    private CreateProductCommand definition(String name) {
        return definition(name, "1.00");
    }

    private CreateProductCommand definition(String name, String makingCommission) {
        return new CreateProductCommand(
                name, "示例说明", WeightGram.ofGrams("100"), "STAR_2", 30,
                SettlementMoney.of(new BigDecimal("50"), CurrencyCode.CNY), LossRate.of("0.05"),
                cost("0.10"), cost("0.20"), cost(makingCommission), 3, 8,
                cost("0.20"), 5, cost("0.50"), cost("0.10"), cost("0.05"), cost("0.02"),
                4, cost("0.60"), cost("0.03"), cost("0.01"),
                cost("0.01"), cost("0.02"), cost("0.03"), cost("0.04"), cost("0.05"), cost("0.06"));
    }

    private static CostAmount cost(String value) {
        return CostAmount.of(new BigDecimal(value), CurrencyCode.CNY);
    }
}
