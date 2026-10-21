package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.WeightGram;
import com.yumi.product.api.CreateProductCommand;
import com.yumi.product.api.ProductRef;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.product.api.SchemeType;
import com.yumi.product.api.SchemeView;
import com.yumi.product.api.UpdateProductCommand;
import com.yumi.product.application.ProductBudgetMapper;
import com.yumi.product.application.ProductCommandService;
import com.yumi.product.application.ProductSchemeCommandService;
import com.yumi.product.application.ProductSnapshotServiceImpl;
import com.yumi.product.infrastructure.JdbcBudgetRepository;
import com.yumi.product.infrastructure.JdbcProductRepository;
import com.yumi.product.infrastructure.JdbcProductSnapshotRepository;
import com.yumi.product.infrastructure.JdbcSchemeRepository;
import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import com.yumi.support.RecordingNumbering;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 商品/方案内容版本快照与历史人工引用（PR-05 的前置）。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §9.5、§12、§7.4。真实 MySQL 验证：
 * 相同版本复用、定义变更不改旧快照、无条件重复生成被防止、并发只产生一条。
 */
class ProductSnapshotIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ProductCommandService products;

    private ProductSchemeCommandService schemes;

    private ProductSnapshotService snapshots;

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
        snapshots = new ProductSnapshotServiceImpl(
                new JdbcProductSnapshotRepository(jdbc), products, schemes, authorization,
                new com.fasterxml.jackson.databind.ObjectMapper(), Clock.systemUTC());
    }

    @Test
    @DisplayName("相同内容版本重复取得返回同一快照，不重复生成")
    void sameVersionReusesSnapshot() {
        ProductRef product = createProduct();
        ProductSnapshotRef first = snapshots.acquireProductSnapshot(product.productBusinessId());
        ProductSnapshotRef second = snapshots.acquireProductSnapshot(product.productBusinessId());

        assertThat(second.snapshotBusinessId()).isEqualTo(first.snapshotBusinessId());
        assertThat(countProductSnapshots()).isEqualTo(1);
    }

    @Test
    @DisplayName("商品定义变更后产生新快照，旧快照内容不变")
    void definitionChangeCreatesNewSnapshotWithoutTouchingOld() {
        ProductRef product = createProduct();
        ProductSnapshotRef oldSnapshot = snapshots.acquireProductSnapshot(product.productBusinessId());
        var oldView = snapshots.resolveProduct(oldSnapshot);

        products.update(new UpdateProductCommand(
                product.productBusinessId(), definition("示例商品（改）", "30", "2.50"),
                product.rowVersion()));

        ProductSnapshotRef newSnapshot = snapshots.acquireProductSnapshot(product.productBusinessId());
        var newView = snapshots.resolveProduct(newSnapshot);

        assertThat(newSnapshot.snapshotBusinessId()).isNotEqualTo(oldSnapshot.snapshotBusinessId());
        assertThat(newSnapshot.snapshotVersion()).isGreaterThan(oldSnapshot.snapshotVersion());
        assertThat(snapshots.resolveProduct(oldSnapshot).name())
                .as("历史快照不被当前定义覆盖")
                .isEqualTo(oldView.name());
        assertThat(oldView.makingCommission().value()).isEqualByComparingTo("1.00");
        assertThat(newView.makingCommission().value()).isEqualByComparingTo("2.50");
        assertThat(newView.name()).isEqualTo("示例商品（改）");
    }

    @Test
    @DisplayName("方案快照冻结目标工序、供料方与预算，商品变更不影响旧方案快照")
    void schemeSnapshotFreezesBasis() {
        ProductRef product = createProduct();
        SchemeView custom = createCustomScheme(product);
        var acquired = snapshots.acquireSchemeSnapshot(custom.schemeBusinessId());

        assertThat(acquired.ref().snapshotType()).isEqualTo(ProductSnapshotRef.SnapshotType.SCHEME);
        assertThat(acquired.view().targetProcess()).isEqualTo(SchemeView.ProcessCode.MAKING);
        assertThat(acquired.view().glueSource()).isEqualTo(com.yumi.product.api.MaterialSource.CUSTOMER);
        assertThat(acquired.view().budget().lines()).isNotEmpty();

        products.update(new UpdateProductCommand(
                product.productBusinessId(), definition("示例商品（改）", "30", "2.50"),
                product.rowVersion()));

        var resolved = snapshots.resolveScheme(acquired.ref());
        assertThat(resolved.makingCommission().value())
                .as("历史方案快照保留原人工标准")
                .isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("并发取得同一快照只产生一条记录")
    void concurrentAcquireCreatesSingleSnapshot() throws Exception {
        ProductRef product = createProduct();
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<String>> tasks = new java.util.ArrayList<>();
        for (int index = 0; index < threads; index++) {
            tasks.add(() -> {
                start.await();
                return snapshots.acquireProductSnapshot(product.productBusinessId()).snapshotBusinessId();
            });
        }
        List<Future<String>> futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Future<String> future : futures) {
            try {
                ids.add(future.get(60, TimeUnit.SECONDS));
            } catch (java.util.concurrent.ExecutionException conflict) {
                // 并发冲突由唯一键裁决；允许失败但不得产生第二条记录
            }
        }
        executor.shutdownNow();

        assertThat(countProductSnapshots()).isEqualTo(1);
        assertThat(ids).hasSizeLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("快照包含单日产能与其他任务单件人工费，供生产与工资引用")
    void snapshotCarriesCapacityAndOtherTaskLabor() {
        ProductRef product = createProduct();
        var view = snapshots.resolveProduct(
                snapshots.acquireProductSnapshot(product.productBusinessId()));

        assertThat(view.dailyCapacity()).isEqualTo(24);
        assertThat(view.otherTaskLaborCost().value()).isEqualByComparingTo("0.0100");
        assertThat(view.ref().contentFingerprint().value()).matches("[0-9a-f]{64}");
    }

    private long countProductSnapshots() {
        return jdbc.sql("SELECT COUNT(*) FROM product_snapshots").query(Long.class).single();
    }

    private SchemeView createCustomScheme(ProductRef product) {
        var ref = schemes.createCustomScheme(new com.yumi.product.api.CreateCustomSchemeCommand(
                product.productBusinessId(), "客户供胶水", SchemeView.ProcessCode.MAKING,
                SettlementMoney.of(new BigDecimal("55"), CurrencyCode.CNY),
                com.yumi.product.api.MaterialSource.CUSTOMER, com.yumi.product.api.MaterialSource.STUDIO,
                cost("0.20"), cost("0.10"), com.yumi.product.api.MaterialSource.STUDIO, cost("0.05"),
                com.yumi.product.api.MaterialSource.STUDIO, cost("0.02"), cost("0.03"), cost("0.01"),
                1L));
        return schemes.listSchemes(product.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeBusinessId().equals(ref.schemeBusinessId()))
                .findFirst()
                .orElseThrow();
    }

    private ProductRef createProduct() {
        return products.create(definition("示例商品", "30", "1.00"));
    }

    private CreateProductCommand definition(String name, String baggingMinutes, String makingCommission) {
        return new CreateProductCommand(
                name, "示例说明", WeightGram.ofGrams("100"), "STAR_2", 30,
                SettlementMoney.of(new BigDecimal("50"), CurrencyCode.CNY), LossRate.of("0.05"),
                cost("0.10"), cost("0.20"), cost(makingCommission), 3, 8,
                cost("0.20"), Integer.parseInt(baggingMinutes), cost("0.50"), cost("0.10"),
                cost("0.05"), cost("0.02"), 4, cost("0.60"), cost("0.03"), cost("0.01"),
                cost("0.01"), cost("0.02"), cost("0.03"), cost("0.04"), cost("0.05"), cost("0.06"));
    }

    private static CostAmount cost(String value) {
        return CostAmount.of(new BigDecimal(value), CurrencyCode.CNY);
    }
}
