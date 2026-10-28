package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import com.yumi.product.api.CreateCustomSchemeCommand;
import com.yumi.product.api.CreateProductCommand;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductCostBasisQuery;
import com.yumi.product.api.ProductRef;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.SchemeView;
import com.yumi.product.application.ProductBudgetMapper;
import com.yumi.product.application.ProductCommandService;
import com.yumi.product.application.ProductCostBasisQueryImpl;
import com.yumi.product.application.ProductSchemeCommandService;
import com.yumi.product.application.ProductSnapshotServiceImpl;
import com.yumi.product.infrastructure.JdbcBudgetRepository;
import com.yumi.product.infrastructure.JdbcProductEntryConfigurationRepository;
import com.yumi.product.infrastructure.JdbcProductRepository;
import com.yumi.product.infrastructure.JdbcProductSnapshotRepository;
import com.yumi.product.infrastructure.JdbcSchemeRepository;
import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import com.yumi.support.RecordingNumbering;
import java.math.BigDecimal;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 最终供料依据、当前工序材料专用解析与完整参考分项（PR-04、PR-05）。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §7.4、§6、§12。真实 MySQL 验证：
 * 订单供料覆盖贯穿成本输入；材料专用解析只取当前工序工作室非人工行；完整参考含人工与分摊；
 * 历史价格变化不回读当前值。
 */
class ProductionBasisIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ProductCommandService products;

    private ProductSchemeCommandService schemes;

    private ProductSnapshotServiceImpl snapshots;

    private ProductCostBasisQuery costBasis;

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
                new JdbcProductEntryConfigurationRepository(jdbc), new RecordingNumbering("P-"), authorization, identity, audit, mapper,
                Clock.systemUTC(), transactionManager);
        schemes = new ProductSchemeCommandService(
                schemeRepository, budgetRepository, products, authorization, identity, audit, mapper,
                Clock.systemUTC(), transactionManager);
        snapshots = new ProductSnapshotServiceImpl(
                new JdbcProductSnapshotRepository(jdbc), products, schemes, authorization,
                new com.fasterxml.jackson.databind.ObjectMapper(), Clock.systemUTC());
        costBasis = new ProductCostBasisQueryImpl(snapshots, authorization);
    }

    @Test
    @DisplayName("订单供料覆盖贯穿成本输入：客户供胶水不进入工作室材料成本")
    void orderSupplyOverrideFlowsIntoCostBasis() {
        ProductRef product = createProduct();
        SchemeView standard = standardScheme(product);
        var schemeSnapshot = snapshots.acquireSchemeSnapshot(standard.schemeBusinessId());

        var basis = costBasis.resolveProductionBasis(
                schemeSnapshot.ref(),
                SchemeView.ProcessCode.MAKING,
                new ProductCostBasisQuery.MaterialSourceAgreement(
                        MaterialSource.CUSTOMER, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO));

        assertThat(basis.targetProcess()).isEqualTo(SchemeView.ProcessCode.MAKING);
        assertThat(basis.materialSources().glue()).isEqualTo(MaterialSource.CUSTOMER);
        assertThat(basis.costLines()).anySatisfy(line -> {
            if (line.componentCode().equals("GLUE")) {
                assertThat(line.source()).isEqualTo(MaterialSource.CUSTOMER);
                assertThat(line.fullResourceCost().value())
                        .as("客户供料仍保留标准价值")
                        .isEqualByComparingTo("10.5000");
            }
        });
        assertThat(basis.contentFingerprint().value()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("当前工序材料解析只取工作室非人工行，不回退方案默认供料方")
    void processCostBasisFiltersStudioNonLaborOfCurrentProcess() {
        ProductRef product = createProduct();
        SchemeView standard = standardScheme(product);
        var schemeSnapshot = snapshots.acquireSchemeSnapshot(standard.schemeBusinessId());

        var basis = costBasis.resolveProductionBasis(
                schemeSnapshot.ref(),
                SchemeView.ProcessCode.MAKING,
                new ProductCostBasisQuery.MaterialSourceAgreement(
                        MaterialSource.CUSTOMER, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO));
        var processCost = costBasis.resolveProductionProcessCostBasis(
                basis, SchemeView.ProcessCode.MAKING);

        assertThat(processCost.studioNonLaborLines())
                .extracting(ProductCostBasisQuery.BasisCostLine::componentCode)
                .as("客户供胶水被排除；人工与额外费用被排除；色浆与制作其他材料保留")
                .containsExactly("COLOR_PASTE", "MAKING_OTHER_MATERIAL");
        assertThat(processCost.totalStudioNonLaborCost().value())
                .as("色浆 21.00 + 制作其他材料 0.20 = 21.20")
                .isEqualByComparingTo("21.2000");
    }

    @Test
    @DisplayName("完整参考分项同时含人工与分摊，不套用材料入账的非人工过滤")
    void referenceLinesIncludeLaborAndExtras() {
        ProductRef product = createProduct();
        SchemeView standard = standardScheme(product);
        var schemeSnapshot = snapshots.acquireSchemeSnapshot(standard.schemeBusinessId());
        var basis = costBasis.resolveProductionBasis(
                schemeSnapshot.ref(), null, null);

        var references = costBasis.resolveReferenceCostLines(basis);

        assertThat(references).isNotEmpty();
        assertThat(references).anySatisfy(line -> assertThat(line.nature()).isEqualTo("LABOR"));
        assertThat(references).anySatisfy(line -> assertThat(line.nature()).isEqualTo("EXTRA_PIECE_FEE"));
        assertThat(references).allSatisfy(line -> {
            assertThat(line.standardValue()).isPresent();
            assertThat(line.missingReason()).isNull();
        });
        // 不返回实际扣款
        assertThat(references.toString()).doesNotContain("deduction", "扣款金额");
    }

    @Test
    @DisplayName("历史价格变化不回读当前值：依据取自快照")
    void basisUsesSnapshotNotCurrentPrice() {
        ProductRef product = createProduct();
        SchemeView standard = standardScheme(product);
        var schemeSnapshot = snapshots.acquireSchemeSnapshot(standard.schemeBusinessId());

        products.update(new com.yumi.product.api.UpdateProductCommand(
                product.productBusinessId(),
                definition("示例商品（新价）", "0.99"), product.rowVersion()));

        var basis = costBasis.resolveProductionBasis(
                schemeSnapshot.ref(), null, null);
        var processCost = costBasis.resolveProductionProcessCostBasis(
                basis, SchemeView.ProcessCode.MAKING);

        assertThat(processCost.totalStudioNonLaborCost().value())
                .as("胶水 10.50 + 色浆 21.00 + 制作其他材料 0.20 = 31.70，不随当前新价 0.99 变化")
                .isEqualByComparingTo("31.7000");
    }

    @Test
    @DisplayName("材料来源约定必须完整，不能用空值表示默认供料方")
    void materialAgreementMustBeComplete() {
        assertThatThrownBy(() -> new ProductCostBasisQuery.MaterialSourceAgreement(
                null, MaterialSource.STUDIO, MaterialSource.STUDIO, MaterialSource.STUDIO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须完整给出");
    }

    @Test
    @DisplayName("依据缺失或工序不匹配时明确失败，不以零成本兜底")
    void missingBasisFailsExplicitly() {
        assertThatThrownBy(() -> costBasis.resolveProductionBasis(null, null, null))
                .isInstanceOf(ApiException.class);

        ProductRef product = createProduct();
        SchemeView standard = standardScheme(product);
        var schemeSnapshot = snapshots.acquireSchemeSnapshot(standard.schemeBusinessId());
        var basis = costBasis.resolveProductionBasis(schemeSnapshot.ref(), null, null);

        assertThatThrownBy(() -> costBasis.resolveProductionProcessCostBasis(basis, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("相同输入得到相同依据指纹；供料方变化则指纹变化")
    void fingerprintIsStable() {
        ProductRef product = createProduct();
        SchemeView standard = standardScheme(product);
        var schemeSnapshot = snapshots.acquireSchemeSnapshot(standard.schemeBusinessId());

        var first = costBasis.resolveProductionBasis(schemeSnapshot.ref(), null, null);
        var second = costBasis.resolveProductionBasis(schemeSnapshot.ref(), null, null);
        var overridden = costBasis.resolveProductionBasis(
                schemeSnapshot.ref(), null,
                new ProductCostBasisQuery.MaterialSourceAgreement(
                        MaterialSource.CUSTOMER, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO));

        assertThat(second.contentFingerprint()).isEqualTo(first.contentFingerprint());
        assertThat(overridden.contentFingerprint()).isNotEqualTo(first.contentFingerprint());
    }

    private SchemeView standardScheme(ProductRef product) {
        return schemes.listSchemes(product.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeType() == com.yumi.product.api.SchemeType.SYSTEM_STANDARD)
                .findFirst()
                .orElseThrow();
    }

    private ProductRef createProduct() {
        return products.create(definition("示例商品", "0.10"));
    }

    private CreateProductCommand definition(String name, String gluePrice) {
        return new CreateProductCommand(
                name, "示例说明", WeightGram.ofGrams("100"), "STAR_2", 30,
                SettlementMoney.of(new BigDecimal("50"), CurrencyCode.CNY), LossRate.of("0.05"),
                cost(gluePrice), cost("0.20"), cost("1.00"), 3, 8,
                cost("0.20"), 5, cost("0.50"), cost("0.10"), cost("0.05"), cost("0.02"),
                4, cost("0.60"), cost("0.03"), cost("0.01"),
                cost("0.01"), cost("0.02"), cost("0.03"), cost("0.04"), cost("0.05"), cost("0.06"));
    }

    private static CostAmount cost(String value) {
        return CostAmount.of(new BigDecimal(value), CurrencyCode.CNY);
    }
}
