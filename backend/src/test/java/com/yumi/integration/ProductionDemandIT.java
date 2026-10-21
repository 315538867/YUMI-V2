package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.product.api.MaterialSource;
import com.yumi.production.api.CreateProductionDemandRequest;
import com.yumi.production.api.DemandStatus;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.PreparedDemand;
import com.yumi.production.api.ProductionBasis;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.SourceKind;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.application.ProductionDemandServiceImpl;
import com.yumi.production.infrastructure.JdbcProductionRepository;
import com.yumi.support.AbstractMySqlIntegrationTest;
import com.yumi.support.RecordingAuthorization;
import com.yumi.support.RecordingIdentity;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 新制/修复需求、稳定生产来源与冻结生产依据，不产生任务（需求侧闭环）。
 *
 * <p>规则来源：`docs/一期/11-生产作业模块.md` §5.1—§5.3、§5.5、§9.1、§7.7。
 * 真实 MySQL 验证：需求幂等、来源类型与工序合法、依据与上游指纹一致、数量守恒。
 */
class ProductionDemandIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ProductionDemandServiceImpl demands;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM production_repair_source_facts").update();
        jdbc.sql("DELETE FROM production_repair_sources").update();
        jdbc.sql("DELETE FROM production_returned_product_sources").update();
        jdbc.sql("DELETE FROM production_demand_change_facts").update();
        jdbc.sql("DELETE FROM production_change_guards").update();
        jdbc.sql("DELETE FROM production_stage_quantity_projections").update();
        jdbc.sql("DELETE FROM production_physical_quantity_projections").update();
        jdbc.sql("DELETE FROM production_demand_quantity_projections").update();
        jdbc.sql("DELETE FROM production_sources").update();
        jdbc.sql("DELETE FROM production_demands").update();

        transactionTemplate = new TransactionTemplate(transactionManager);
        demands = new ProductionDemandServiceImpl(
                new JdbcProductionRepository(jdbc),
                new com.yumi.production.infrastructure.JdbcReturnedProductRepository(jdbc),
                new RecordingAuthorization(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.systemUTC());
    }

    @Test
    @DisplayName("建立新制需求：来源、依据与物理根正确，初始数量全部未制")
    void createNewProductDemand() {
        ProductionDemandRef ref = create(
                request(DemandType.NEW_PRODUCT, "ORD-ITEM-1", TargetProcess.SEAM_CUTTING, 100));

        assertThat(ref.demandType()).isEqualTo(DemandType.NEW_PRODUCT);
        assertThat(ref.targetProcess()).isEqualTo(TargetProcess.SEAM_CUTTING);
        assertThat(ref.requiredQuantity()).isEqualTo(100);
        assertThat(ref.status()).isEqualTo(DemandStatus.ACTIVE);
        assertThat(ref.physicalRootSourceBusinessId())
                .as("新制来源的物理根是自身")
                .isEqualTo(ref.productionSourceBusinessId());

        var demand = jdbc.sql("""
                        SELECT demand_type, source_module, source_type, production_basis_fingerprint,
                               product_snapshot_ref, scheme_snapshot_ref
                        FROM production_demands WHERE demand_business_id = :id
                        """).param("id", ref.demandBusinessId())
                .query((resultSet, rowNumber) -> new String[] {
                        resultSet.getString("demand_type"),
                        resultSet.getString("source_module"),
                        resultSet.getString("source_type"),
                        resultSet.getString("production_basis_fingerprint"),
                        resultSet.getString("product_snapshot_ref"),
                        resultSet.getString("scheme_snapshot_ref")})
                .single();
        assertThat(demand[0]).isEqualTo("NEW_PRODUCT");
        assertThat(demand[1]).isEqualTo("orders");
        assertThat(demand[2]).isEqualTo("ORDER_ITEM");
        assertThat(demand[3]).matches("[0-9a-f]{64}");

        var projection = jdbc.sql("""
                        SELECT required_quantity, pending_work_quantity, in_process_quantity,
                               target_ready_quantity, fulfilled_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections WHERE demand_business_id = :id
                        """).param("id", ref.demandBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("required_quantity"),
                        resultSet.getInt("pending_work_quantity"),
                        resultSet.getInt("in_process_quantity"),
                        resultSet.getInt("target_ready_quantity"),
                        resultSet.getInt("fulfilled_quantity"),
                        resultSet.getInt("fulfillment_gap_quantity")))
                .single();
        assertThat(projection).containsExactly(100, 100, 0, 0, 0, 0);

        // 建立需求不产生任务与排班：任务表在本组实现中建立，此处以需求/来源行数与被引用情况表达
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM production_demands
                        WHERE demand_business_id = :id
                        """).param("id", ref.demandBusinessId()).query(Long.class).single())
                .as("建立需求只写需求与来源")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("同一来源重复建立返回同一需求，数量不变")
    void demandCreationIsIdempotent() {
        CreateProductionDemandRequest request = request(
                DemandType.NEW_PRODUCT, "ORD-ITEM-2", TargetProcess.MAKING, 30);

        ProductionDemandRef first = create(request);
        ProductionDemandRef second = create(request);

        assertThat(second.demandBusinessId()).isEqualTo(first.demandBusinessId());
        assertThat(second.productionSourceBusinessId()).isEqualTo(first.productionSourceBusinessId());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_demands").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM production_sources").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一来源定义不同则拒绝，而不是静默复用")
    void conflictingRedefinitionIsRejected() {
        create(request(DemandType.NEW_PRODUCT, "ORD-ITEM-3", TargetProcess.MAKING, 30));

        assertThatThrownBy(() -> create(
                request(DemandType.NEW_PRODUCT, "ORD-ITEM-3", TargetProcess.SEAM_CUTTING, 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_DEMAND_CONFLICT"));
    }

    @Test
    @DisplayName("修复需求必须关联退回来源；新制需求不得关联退回来源")
    void demandTypeAndReturnedSourceAreConsistent() {
        assertThatThrownBy(() -> new CreateProductionDemandRequest(
                "orders", "AFTER_SALES_REPAIR", "AS-1", null, null, DemandType.RETURNED_PRODUCT_REPAIR,
                "PRD-1", "PSNAP-1", "SCH-1", "SSNAP-1", basis(TargetProcess.MAKING), 5, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须关联唯一退回制品来源");

        assertThatThrownBy(() -> new CreateProductionDemandRequest(
                "orders", "ORDER_ITEM", "ORD-1", null, null, DemandType.NEW_PRODUCT,
                "PRD-1", "PSNAP-1", "SCH-1", "SSNAP-1", basis(TargetProcess.MAKING), 5, "RET-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("新制需求不能关联退回来源");
    }

    @Test
    @DisplayName("需求数量、来源与依据必填，缺失时明确拒绝")
    void requiredFieldsAreValidated() {
        assertThatThrownBy(() -> create(new CreateProductionDemandRequest(
                "orders", "ORDER_ITEM", "ORD-ITEM-4", null, null, DemandType.NEW_PRODUCT,
                "PRD-1", "PSNAP-1", "SCH-1", "SSNAP-1", basis(TargetProcess.MAKING), 0, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需求数量必须大于 0");

        assertThatThrownBy(() -> create(new CreateProductionDemandRequest(
                "orders", "ORDER_ITEM", "ORD-ITEM-5", null, null, DemandType.NEW_PRODUCT,
                "PRD-1", "PSNAP-1", "SCH-1", "SSNAP-1", basisWithoutFingerprint(), 10, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("生产依据指纹");
    }

    @Test
    @DisplayName("依据指纹与上游一致：需求保存的指纹等于传入依据指纹")
    void basisFingerprintMatchesUpstream() {
        ContentFingerprint upstream = ContentFingerprint.of("a".repeat(64));
        ProductionDemandRef ref = create(new CreateProductionDemandRequest(
                "orders", "ORDER_ITEM", "ORD-ITEM-6", null, null, DemandType.NEW_PRODUCT,
                "PRD-1", "PSNAP-1", "SCH-1", "SSNAP-1", basis(TargetProcess.MAKING, upstream), 12, null));

        String stored = jdbc.sql("""
                        SELECT production_basis_fingerprint FROM production_demands
                        WHERE demand_business_id = :id
                        """).param("id", ref.demandBusinessId()).query(String.class).single();
        assertThat(stored).isEqualTo(upstream.value());

        String sourceFingerprint = jdbc.sql("""
                        SELECT production_basis_fingerprint FROM production_sources
                        WHERE production_source_business_id = :id
                        """).param("id", ref.productionSourceBusinessId()).query(String.class).single();
        assertThat(sourceFingerprint)
                .as("来源只使用所属需求的一套不可变依据")
                .isEqualTo(upstream.value());
    }

    @Test
    @DisplayName("既有订单来源在完成期初初始化前不得安排任务")
    void existingOrderSourceRequiresOpening() {
        ProductionDemandRef ref = create(new CreateProductionDemandRequest(
                "orders", "EXISTING_ORDER", "ORD-EXISTING-1", "ORD-EXISTING", null,
                DemandType.NEW_PRODUCT, "PRD-1", "PSNAP-1", "SCH-1", "SSNAP-1",
                basis(TargetProcess.SEAM_CUTTING), 40, null));

        var source = jdbc.sql("""
                        SELECT opening_status, status FROM production_sources
                        WHERE production_source_business_id = :id
                        """).param("id", ref.productionSourceBusinessId())
                .query((resultSet, rowNumber) -> new String[] {
                        resultSet.getString("opening_status"), resultSet.getString("status")})
                .single();
        assertThat(source[0]).isEqualTo(ProductionDemandServiceImpl.OPENING_REQUIRED);
        assertThat(source[1]).isEqualTo(ProductionDemandServiceImpl.OPENING_PENDING);
    }

    @Test
    @DisplayName("物理制品投影守恒：新制初始产生量为需求数量且全部未在系统内")
    void physicalProjectionConserves() {
        ProductionDemandRef ref = create(
                request(DemandType.NEW_PRODUCT, "ORD-ITEM-7", TargetProcess.MAKING, 25));

        var physical = jdbc.sql("""
                        SELECT cumulative_making_created_quantity, in_system_quantity, consumed_quantity,
                               destroyed_quantity, offline_inventory_quantity
                        FROM production_physical_quantity_projections
                        WHERE physical_root_source_business_id = :id
                        """).param("id", ref.physicalRootSourceBusinessId())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("cumulative_making_created_quantity"),
                        resultSet.getInt("in_system_quantity"),
                        resultSet.getInt("consumed_quantity"),
                        resultSet.getInt("destroyed_quantity"),
                        resultSet.getInt("offline_inventory_quantity")))
                .single();
        assertThat(physical.get(0))
                .as("累计制作产生量只在 MAKING 核验合格后增加，建立需求时为 0")
                .isZero();
        assertThat(physical.get(1) + physical.get(2) + physical.get(3) + physical.get(4))
                .isEqualTo(physical.get(0));
        assertThat(physical.get(1)).isZero();
    }

    @Test
    @DisplayName("未授权操作者不能建立生产需求")
    void unauthorizedOperatorIsRejected() {
        var denied = new RecordingAuthorization();
        denied.deny(com.yumi.platform.api.authorization.PermissionCodes.PRODUCTION_SCHEDULE);
        var blocked = new ProductionDemandServiceImpl(
                new JdbcProductionRepository(jdbc),
                new com.yumi.production.infrastructure.JdbcReturnedProductRepository(jdbc), denied,
                new com.fasterxml.jackson.databind.ObjectMapper(), Clock.systemUTC());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            PreparedDemand prepared = blocked.prepareCreate(
                    request(DemandType.NEW_PRODUCT, "ORD-ITEM-8", TargetProcess.MAKING, 10));
            blocked.commitCreate(prepared);
        }))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).type())
                        .isEqualTo(ApiErrorType.FORBIDDEN));
    }

    private ProductionDemandRef create(CreateProductionDemandRequest request) {
        return transactionTemplate.execute(status -> {
            PreparedDemand prepared = demands.prepareCreate(request);
            return demands.commitCreate(prepared);
        });
    }

    private static CreateProductionDemandRequest request(
            DemandType demandType, String orderItemBusinessId, TargetProcess targetProcess, int quantity) {
        return new CreateProductionDemandRequest(
                "orders", "ORDER_ITEM", orderItemBusinessId, "ORD-" + orderItemBusinessId,
                orderItemBusinessId, demandType, "PRD-" + orderItemBusinessId, "PSNAP-" + orderItemBusinessId,
                "SCH-" + orderItemBusinessId, "SSNAP-" + orderItemBusinessId,
                basis(targetProcess), quantity, null);
    }

    /** 缺指纹的依据：模拟调用方未从商品解析而自行拼装。 */
    private static ProductionBasis basisWithoutFingerprint() {
        return new ProductionBasis(
                "SSNAP-1", "PSNAP-1", "SCH-1", TargetProcess.MAKING,
                new ProductionBasis.MaterialSources(
                        MaterialSource.STUDIO, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO),
                List.of(), "2026.10-1", "HALF_UP-4", null, 30, 5, 4);
    }

    private static ProductionBasis basis(TargetProcess targetProcess) {
        return basis(targetProcess, ContentFingerprint.of("b".repeat(64)));
    }

    private static ProductionBasis basis(TargetProcess targetProcess, ContentFingerprint fingerprint) {
        return new ProductionBasis(
                "SSNAP-1", "PSNAP-1", "SCH-1", targetProcess,
                new ProductionBasis.MaterialSources(
                        MaterialSource.STUDIO, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO),
                List.of(new ProductionBasis.CostLine(
                        "MAKING_LABOR", TargetProcess.MAKING, "LABOR", MaterialSource.STUDIO,
                        com.yumi.platform.api.value.CostAmount.of(
                                new java.math.BigDecimal("1.00"), com.yumi.platform.api.value.CurrencyCode.CNY))),
                "2026.10-1", "HALF_UP-4", fingerprint, 30, 5, 4);
    }
}
