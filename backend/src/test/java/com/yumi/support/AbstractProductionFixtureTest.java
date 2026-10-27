package com.yumi.support;

import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.identity.bootstrap.InitialAdministratorCommand;
import com.yumi.platform.identity.infrastructure.CurrentActorHolder;
import com.yumi.product.api.MaterialSource;
import com.yumi.production.api.CreateProductionDemandRequest;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.PreparedDemand;
import com.yumi.production.api.ProductionBasis;
import com.yumi.production.api.ProductionDemandParticipant;
import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.api.TaskType;
import com.yumi.workforce.api.ChangeQualificationCommand;
import com.yumi.workforce.api.EmployeeRef;
import com.yumi.workforce.api.HireEmployeeCommand;
import com.yumi.workforce.api.WorkforceApplicationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 生产模块集成测试的公共装配：真实平台认证、真实人员与需求服务。
 *
 * <p>只用于测试；它不替代任何生产代码路径：全部调用都经过真实应用服务与真实 MySQL。
 */
public abstract class AbstractProductionFixtureTest extends AbstractMySqlIntegrationTest {

    protected static final String ADMIN_LOGIN = "it-admin";
    protected static final String ADMIN_PASSWORD = "it-pass-1234";

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected InitialAdministratorCommand bootstrap;

    @Autowired
    protected WorkforceApplicationService workforce;

    @Autowired
    protected ProductionDemandParticipant demandParticipant;

    @Autowired
    protected com.yumi.product.api.ProductApplicationService productService;

    @Autowired
    protected com.yumi.product.api.ProductSnapshotService productSnapshots;

    @Autowired
    protected com.yumi.product.api.ProductSchemeApplicationService productSchemes;

    /** 指定商品的内置标准方案（固定 SEAM_CUTTING 终点）。 */
    protected String standardSchemeId(com.yumi.product.api.ProductRef product) {
        return productSchemes.listSchemes(product.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeType() == com.yumi.product.api.SchemeType.SYSTEM_STANDARD)
                .findFirst()
                .orElseThrow()
                .schemeBusinessId();
    }

    /** 默认商品的内置标准方案（固定 SEAM_CUTTING 终点）。 */
    protected String standardSchemeId() {
        return productSchemes.listSchemes(defaultProduct.productBusinessId()).stream()
                .filter(scheme -> scheme.schemeType() == com.yumi.product.api.SchemeType.SYSTEM_STANDARD)
                .findFirst()
                .orElseThrow()
                .schemeBusinessId();
    }

    @Autowired
    protected com.yumi.workforce.api.AssignmentSubjectSnapshotService assignmentSnapshots;

    @Autowired
    protected com.yumi.platform.identity.application.IdentityApplicationService identity;

    protected TransactionTemplate transactionTemplate;

    protected String adminToken;

    /** 默认商品：产能 4 模具 × 10 批 = 40/日。 */
    protected com.yumi.product.api.ProductRef defaultProduct;

    @BeforeEach
    void prepareEnvironment() {
        jdbc.sql("DELETE FROM workforce_activity_reservations").update();
        jdbc.sql("DELETE FROM workforce_activity_reference_guards").update();
        jdbc.sql("DELETE FROM production_offline_inventory_transfers").update();
        jdbc.sql("DELETE FROM production_consumption_facts").update();
        jdbc.sql("DELETE FROM production_fact_correction_applications").update();
        jdbc.sql("DELETE FROM production_fact_correction_items").update();
        jdbc.sql("DELETE FROM production_fact_corrections").update();
        jdbc.sql("DELETE FROM production_responsibility_revisions").update();
        jdbc.sql("DELETE FROM production_material_cost_sources").update();
        jdbc.sql("DELETE FROM production_labor_sources").update();
        jdbc.sql("DELETE FROM production_other_task_verification_facts").update();
        jdbc.sql("DELETE FROM production_responsibility_facts").update();
        jdbc.sql("DELETE FROM production_terminal_exits").update();
        jdbc.sql("DELETE FROM production_disposition_facts").update();
        jdbc.sql("DELETE FROM production_defect_facts").update();
        jdbc.sql("DELETE FROM production_quality_facts").update();
        jdbc.sql("DELETE FROM production_execution_facts").update();
        jdbc.sql("DELETE FROM production_task_item_verifications").update();
        jdbc.sql("DELETE FROM production_source_holds").update();
        jdbc.sql("DELETE FROM production_source_reservations").update();
        jdbc.sql("DELETE FROM production_task_cancellation_facts").update();
        jdbc.sql("DELETE FROM production_employee_daily_task_slots").update();
        jdbc.sql("DELETE FROM production_employee_day_work_modes").update();
        jdbc.sql("DELETE FROM production_active_participant_references").update();
        jdbc.sql("DELETE FROM production_active_subject_references").update();
        jdbc.sql("DELETE FROM production_product_day_capacity_usages").update();
        jdbc.sql("DELETE FROM production_employee_day_loads").update();
        jdbc.sql("DELETE FROM production_assessment_member_loads").update();
        jdbc.sql("DELETE FROM production_workload_assessment_items").update();
        jdbc.sql("DELETE FROM production_workload_assessments").update();
        jdbc.sql("DELETE FROM production_task_item_assignment_facts").update();
        jdbc.sql("DELETE FROM production_task_assignment_facts").update();
        jdbc.sql("DELETE FROM production_other_task_details").update();
        jdbc.sql("DELETE FROM production_task_items").update();
        jdbc.sql("DELETE FROM production_tasks").update();
        jdbc.sql("DELETE FROM production_repair_source_facts").update();
        jdbc.sql("DELETE FROM production_repair_sources").update();
        jdbc.sql("DELETE FROM production_returned_product_sources").update();
        jdbc.sql("DELETE FROM production_returned_pool_transfers").update();
        jdbc.sql("DELETE FROM production_returned_pool_consumption_facts").update();
        jdbc.sql("DELETE FROM production_returned_product_disposition_facts").update();
        jdbc.sql("DELETE FROM production_return_reentry_facts").update();
        jdbc.sql("DELETE FROM finance_daily_cash_details").update();
        jdbc.sql("DELETE FROM finance_payroll_payment_returns").update();
        jdbc.sql("DELETE FROM finance_payroll_return_details").update();
        jdbc.sql("DELETE FROM finance_cash_voucher_links").update();
        jdbc.sql("DELETE FROM finance_cash_allocations").update();
        jdbc.sql("DELETE FROM finance_source_projections").update();
        jdbc.sql("DELETE FROM finance_source_guards").update();
        jdbc.sql("DELETE FROM finance_cash_facts").update();
        jdbc.sql("DELETE FROM finance_cost_allocations").update();
        jdbc.sql("DELETE FROM finance_cost_failures").update();
        jdbc.sql("DELETE FROM finance_cost_source_guards").update();
        jdbc.sql("DELETE FROM finance_cost_entry_lines").update();
        jdbc.sql("DELETE FROM finance_cost_entries").update();
        jdbc.sql("DELETE FROM finance_operating_expenses").update();
        jdbc.sql("DELETE FROM finance_payroll_payment_projections").update();
        jdbc.sql("DELETE FROM finance_payroll_payment_links").update();
        jdbc.sql("DELETE FROM finance_direct_labor_cost_adjustments").update();
        jdbc.sql("DELETE FROM finance_payroll_adjustments").update();
        jdbc.sql("DELETE FROM finance_direct_labor_costs").update();
        jdbc.sql("DELETE FROM finance_payroll_processing_items").update();
        jdbc.sql("DELETE FROM finance_payroll_confirmations").update();
        jdbc.sql("DELETE FROM finance_payroll_glue_month_lines").update();
        jdbc.sql("DELETE FROM finance_payroll_glue_month_references").update();
        jdbc.sql("DELETE FROM finance_payroll_glue_policies").update();
        jdbc.sql("DELETE FROM finance_payroll_glue_policy_guard").update();
        jdbc.sql("DELETE FROM finance_payroll_reference_lines").update();
        jdbc.sql("DELETE FROM finance_payroll_reference_runs").update();
        jdbc.sql("DELETE FROM finance_payroll_results").update();
        jdbc.sql("DELETE FROM finance_payroll_periods").update();
        jdbc.sql("DELETE FROM finance_payroll_responsibility_entries").update();
        jdbc.sql("DELETE FROM finance_payroll_labor_entries").update();
        jdbc.sql("DELETE FROM finance_payroll_source_cursors").update();
        jdbc.sql("DELETE FROM finance_payroll_month_guards").update();
        jdbc.sql("DELETE FROM production_opening_facts").update();
        jdbc.sql("DELETE FROM production_demand_change_facts").update();
        jdbc.sql("DELETE FROM production_change_guards").update();
        jdbc.sql("DELETE FROM production_stage_quantity_projections").update();
        jdbc.sql("DELETE FROM production_physical_quantity_projections").update();
        jdbc.sql("DELETE FROM production_demand_quantity_projections").update();
        jdbc.sql("DELETE FROM production_sources").update();
        jdbc.sql("DELETE FROM production_demands").update();
        jdbc.sql("DELETE FROM workforce_standard_version_facts").update();
        jdbc.sql("DELETE FROM workforce_standard_snapshots").update();
        jdbc.sql("DELETE FROM workforce_team_snapshots").update();
        jdbc.sql("DELETE FROM workforce_employee_snapshots").update();
        jdbc.sql("DELETE FROM workforce_team_status_facts").update();
        jdbc.sql("DELETE FROM workforce_team_member_facts").update();
        jdbc.sql("DELETE FROM workforce_team_members").update();
        jdbc.sql("DELETE FROM workforce_teams").update();
        jdbc.sql("DELETE FROM workforce_qualification_facts").update();
        jdbc.sql("DELETE FROM workforce_qualifications").update();
        jdbc.sql("DELETE FROM workforce_employment_facts").update();
        jdbc.sql("DELETE FROM workforce_employees").update();
        // 共享夹具统一清理业务表：本地库持久化后，跨测试类残留会互相污染。
        // 删除顺序按外键依赖从子到父。
        jdbc.sql("DELETE FROM customer_change_facts").update();
        jdbc.sql("DELETE FROM customer_snapshots").update();
        jdbc.sql("DELETE FROM customer_customers").update();
        jdbc.sql("DELETE FROM product_scheme_snapshots").update();
        jdbc.sql("DELETE FROM product_snapshots").update();
        jdbc.sql("DELETE FROM product_budget_result_lines").update();
        jdbc.sql("DELETE FROM product_budget_results").update();
        jdbc.sql("DELETE FROM product_scheme_bagging").update();
        jdbc.sql("DELETE FROM product_scheme_seam_cutting").update();
        jdbc.sql("DELETE FROM product_scheme_making").update();
        jdbc.sql("DELETE FROM product_scheme_extra_unit_costs").update();
        jdbc.sql("DELETE FROM product_schemes").update();
        jdbc.sql("DELETE FROM product_extra_unit_costs").update();
        jdbc.sql("DELETE FROM product_bagging_processes").update();
        jdbc.sql("DELETE FROM product_seam_cutting_processes").update();
        jdbc.sql("DELETE FROM product_making_processes").update();
        jdbc.sql("DELETE FROM product_products").update();
        // 录入默认值与选项是种子数据，跨运行会累积 row_version；恢复为基线版本 1
        jdbc.sql("UPDATE product_entry_defaults SET glue_loss_rate = 0.05000000,"
                + " glue_unit_price = 0.1000, color_paste_unit_price = 0.2000, row_version = 1").update();
        jdbc.sql("UPDATE product_making_star_options SET row_version = 1").update();
        jdbc.sql("UPDATE product_process_time_options SET row_version = 1").update();
        jdbc.sql("DELETE FROM platform_idempotency_records").update();
        jdbc.sql("DELETE FROM platform_audit_logs").update();
        jdbc.sql("DELETE FROM platform_sessions").update();
        jdbc.sql("DELETE FROM platform_account_roles").update();
        jdbc.sql("DELETE FROM platform_accounts").update();
        jdbc.sql("UPDATE workforce_team_efficiency_standards SET member_count = 2,"
                + " efficiency_factor = 1.2, content_version = 1, row_version = 1"
                + " WHERE standard_business_id = 'WFE-2'").update();
        jdbc.sql("UPDATE workforce_team_efficiency_standards SET member_count = 3,"
                + " efficiency_factor = 1.3, content_version = 1, row_version = 1"
                + " WHERE standard_business_id = 'WFE-3'").update();
        jdbc.sql("UPDATE workforce_daily_work_time_standard SET effective_minutes = 480,"
                + " content_version = 1, row_version = 1").update();
        jdbc.sql("DELETE FROM production_defect_reasons WHERE defect_reason_code LIKE 'TEST%'").update();
        jdbc.sql("UPDATE production_defect_reasons SET status = 'ACTIVE' WHERE status <> 'ACTIVE'").update();

        transactionTemplate = new TransactionTemplate(transactionManager);
        bootstrap.initialize(ADMIN_LOGIN, ADMIN_PASSWORD);
        adminToken = identity.login(new com.yumi.platform.api.identity.LoginCommand(
                ADMIN_LOGIN, ADMIN_PASSWORD)).token();
        defaultProduct = createProduct("标准商品", 4, 10);
    }

    /**
     * 以系统管理员身份执行命令。
     *
     * <p>先解析真实会话令牌取得当前操作者，再在同一线程内执行；令牌解析失败会直接抛出，
     * 不会被测试吞掉。
     */
    protected <T> T asAdmin(java.util.function.Supplier<T> body) {
        var actor = identity.requireCurrentActor(adminToken);
        return CurrentActorHolder.runAs(actor, body);
    }

    /** 创建可被生产引用的商品；产能由模具数量 × 每日批次得出。 */
    protected com.yumi.product.api.ProductRef createProduct(String name, int mouldCount, int mouldDailyBatches) {
        var money = com.yumi.platform.api.value.CostAmount.of(
                new BigDecimal("1.00"), com.yumi.platform.api.value.CurrencyCode.CNY);
        var settlement = com.yumi.platform.api.value.SettlementMoney.of(
                new BigDecimal("50.00"), com.yumi.platform.api.value.CurrencyCode.CNY);
        return asAdmin(() -> productService.create(new com.yumi.product.api.CreateProductCommand(
                name, "集成测试商品",
                com.yumi.platform.api.value.WeightGram.ofGrams("120.0000"),
                "M1", 30, settlement,
                com.yumi.platform.api.value.LossRate.of(new BigDecimal("0.0500")),
                money, money, money, mouldCount, mouldDailyBatches, money,
                5, money, money, money, money,
                4, money, money, money,
                money, money, money, money, money, money)));
    }

    /** 建立新制需求并返回来源标识。 */
    protected ProductionDemandRef createNewProductDemand(
            String orderItemBusinessId, TargetProcess targetProcess, int quantity) {
        return createDemand(orderItemBusinessId, targetProcess, quantity,
                new BigDecimal("0.10"), new BigDecimal("0.20"));
    }

    /** 建立新制需求，可指定工序标准分钟（用于把大批量控制在单日标准内）。 */
    protected ProductionDemandRef createNewProductDemand(
            String orderItemBusinessId, TargetProcess targetProcess, int quantity,
            com.yumi.product.api.ProductRef product, int makingMinutes) {
        return asAdmin(() -> {
            String schemeId = standardSchemeId(product);
            CreateProductionDemandRequest request = new CreateProductionDemandRequest(
                    "orders", "ORDER_ITEM", orderItemBusinessId, "ORD-" + orderItemBusinessId,
                    orderItemBusinessId, DemandType.NEW_PRODUCT,
                    product.productBusinessId(),
                    productSnapshots.acquireProductSnapshot(product.productBusinessId()).snapshotBusinessId(),
                    schemeId,
                    productSnapshots.acquireSchemeSnapshot(schemeId).ref().snapshotBusinessId(),
                    basis(targetProcess, new BigDecimal("0.10"), new BigDecimal("0.20"), makingMinutes),
                    quantity, null);
            return transactionTemplate.execute(status ->
                    demandParticipant.commitCreate(demandParticipant.prepareCreate(request)));
        });
    }

    /** 建立引用指定商品的新制需求。 */
    protected ProductionDemandRef createNewProductDemand(
            String orderItemBusinessId, TargetProcess targetProcess, int quantity,
            com.yumi.product.api.ProductRef product) {
        return asAdmin(() -> {
            String schemeId = standardSchemeId(product);
            CreateProductionDemandRequest request = new CreateProductionDemandRequest(
                    "orders", "ORDER_ITEM", orderItemBusinessId, "ORD-" + orderItemBusinessId,
                    orderItemBusinessId, DemandType.NEW_PRODUCT,
                    product.productBusinessId(),
                    productSnapshots.acquireProductSnapshot(product.productBusinessId()).snapshotBusinessId(),
                    schemeId,
                    productSnapshots.acquireSchemeSnapshot(schemeId).ref().snapshotBusinessId(),
                    basis(targetProcess, new BigDecimal("0.10"), new BigDecimal("0.20")),
                    quantity, null);
            return transactionTemplate.execute(status ->
                    demandParticipant.commitCreate(demandParticipant.prepareCreate(request)));
        });
    }

    /** 建立新制需求，可指定胶水单价以制造不同窗口指纹。 */
    protected ProductionDemandRef createDemand(
            String orderItemBusinessId, TargetProcess targetProcess, int quantity,
            BigDecimal glueUnitPrice, BigDecimal colorPasteUnitPrice) {
        // 快照与方案查询同样需要当前操作者：整段在管理员身份下执行
        CreateProductionDemandRequest request = asAdmin(() -> new CreateProductionDemandRequest(
                "orders", "ORDER_ITEM", orderItemBusinessId, "ORD-" + orderItemBusinessId,
                orderItemBusinessId, DemandType.NEW_PRODUCT,
                defaultProduct.productBusinessId(),
                productSnapshots.acquireProductSnapshot(defaultProduct.productBusinessId())
                        .snapshotBusinessId(),
                standardSchemeId(),
                productSnapshots.acquireSchemeSnapshot(standardSchemeId()).ref().snapshotBusinessId(),
                basis(targetProcess, glueUnitPrice, colorPasteUnitPrice),
                quantity, null));
        return asAdmin(() -> transactionTemplate.execute(status ->
                demandParticipant.commitCreate(demandParticipant.prepareCreate(request))));
    }

    /** 生产依据：与商品解析结果同形的结构，指纹由调用方输入决定。 */
    protected ProductionBasis basis(
            TargetProcess targetProcess, BigDecimal glueUnitPrice, BigDecimal colorPasteUnitPrice) {
        return basis(targetProcess, glueUnitPrice, colorPasteUnitPrice, 30);
    }

    /** 生产依据，可指定制作标准分钟。 */
    protected ProductionBasis basis(
            TargetProcess targetProcess, BigDecimal glueUnitPrice, BigDecimal colorPasteUnitPrice,
            int makingStandardMinutes) {
        ContentFingerprint fingerprint = ContentFingerprint.of(sha256(
                targetProcess.name() + "|" + glueUnitPrice.toPlainString() + "|"
                        + colorPasteUnitPrice.toPlainString()));
        List<ProductionBasis.CostLine> lines = List.of(
                costLine("GLUE", TargetProcess.MAKING, "NON_LABOR_MATERIAL", MaterialSource.STUDIO,
                        new BigDecimal("10.5000")),
                costLine("COLOR_PASTE", TargetProcess.MAKING, "NON_LABOR_MATERIAL", MaterialSource.STUDIO,
                        new BigDecimal("21.0000")),
                costLine("MAKING_LABOR", TargetProcess.MAKING, "LABOR", MaterialSource.STUDIO,
                        new BigDecimal("1.0000")),
                costLine("BAGGING_LABOR", TargetProcess.BAGGING, "LABOR", MaterialSource.STUDIO,
                        new BigDecimal("0.5000")),
                costLine("SEAM_LABOR", TargetProcess.SEAM_CUTTING, "LABOR", MaterialSource.STUDIO,
                        new BigDecimal("0.6000")));
        return new ProductionBasis(
                "SSNAP", "PSNAP", "SCH", targetProcess,
                new ProductionBasis.MaterialSources(
                        MaterialSource.STUDIO, MaterialSource.STUDIO,
                        MaterialSource.STUDIO, MaterialSource.STUDIO),
                lines, "2026.10-1", "HALF_UP-4", fingerprint, makingStandardMinutes, 5, 4);
    }

    /**
     * 模拟上游工序已经合格流转：把需求投影从“未制”转入“在制”。
     *
     * <p>这是制作核验成功后由生产事实写入的真实状态（基线 11 §5.2.1）；本组测试用它构造
     * “上游已合格、可安排后续工序”的起点，避免依赖尚未实现的核验命令。
     */
    protected void markUpstreamQualified(String demandBusinessId, int quantity) {
        var projection = jdbc.sql("""
                        SELECT required_quantity, pending_work_quantity, in_process_quantity,
                               target_ready_quantity, fulfilled_quantity, fulfillment_gap_quantity
                        FROM production_demand_quantity_projections WHERE demand_business_id = :id
                        """).param("id", demandBusinessId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getInt("required_quantity"),
                        resultSet.getInt("pending_work_quantity"),
                        resultSet.getInt("in_process_quantity")))
                .single();
        jdbc.sql("""
                        UPDATE production_demand_quantity_projections
                        SET pending_work_quantity = :pending, in_process_quantity = :inProcess,
                            updated_at = UTC_TIMESTAMP(6), row_version = row_version + 1
                        WHERE demand_business_id = :id
                        """)
                .param("pending", projection.get(1) - quantity)
                .param("inProcess", projection.get(2) + quantity)
                .param("id", demandBusinessId)
                .update();
    }

    /**
     * 模拟核验后释放来源活动预留与占用。
     *
     * <p>真实流程由核验命令完成（基线 11 §7.4）；本组测试只需构造“上游已核验并释放”的起点。
     */
    protected void releaseSourceReservation(String productionSourceBusinessId, int quantity) {
        jdbc.sql("""
                        UPDATE production_source_reservations
                        SET reserved_quantity = reserved_quantity - :quantity,
                            row_version = row_version + 1
                        WHERE production_source_business_id = :id
                        """)
                .param("quantity", quantity)
                .param("id", productionSourceBusinessId)
                .update();
        jdbc.sql("""
                        UPDATE production_source_holds SET status = 'RELEASED',
                            released_at = UTC_TIMESTAMP(6), row_version = row_version + 1
                        WHERE production_source_business_id = :id AND status = 'ACTIVE'
                        """)
                .param("id", productionSourceBusinessId)
                .update();
    }

    /** 入职并授予指定工种资格的员工。 */
    protected EmployeeRef hireEmployee(String name, String... workTypes) {
        EmployeeRef employee = asAdmin(() -> workforce.hire(new HireEmployeeCommand(
                name, "13800000000", LocalDate.parse("2026-10-01"))));
        for (String workType : workTypes) {
            asAdmin(() -> {
                workforce.grantWorkType(new ChangeQualificationCommand(
                        employee.employeeBusinessId(), workType, LocalDate.parse("2026-10-01")));
                return null;
            });
        }
        return employee;
    }

    /** 常用任务类型到工种的映射。 */
    protected static String workTypeOf(TaskType taskType, TargetProcess processCode) {
        return switch (taskType) {
            case OTHER_TASK -> "OTHER_TASK";
            default -> processCode.name();
        };
    }

    private static ProductionBasis.CostLine costLine(
            String componentCode, TargetProcess process, String nature, MaterialSource source,
            BigDecimal amount) {
        return new ProductionBasis.CostLine(
                componentCode, process, nature, source,
                com.yumi.platform.api.value.CostAmount.of(
                        amount, com.yumi.platform.api.value.CurrencyCode.CNY));
    }

    private static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 供子类断言：当前管理员账号标识。 */
    protected String adminAccountId() {
        return identity.requireCurrentActor(adminToken).accountBusinessId().value();
    }

    /** 常用权限断言辅助。 */
    protected static String schedulePermission() {
        return PermissionCodes.PRODUCTION_SCHEDULE;
    }

    /** 解析稳定标识（便于断言）。 */
    protected static String stable(StableBusinessId id) {
        return id.value();
    }
}
