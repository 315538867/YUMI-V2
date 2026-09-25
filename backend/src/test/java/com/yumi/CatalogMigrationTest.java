package com.yumi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2.1 / 2.21 / 2.24 迁移断言：主数据表与静态数据表（星级、缝边种类、员工工种、包装档位、全局设置），
 * 业务编号唯一、金额 DECIMAL(19,4)、比例 DECIMAL(9,6)、版本与审计字段齐全；
 * 未上线阶段 V4/V5 已重写为最终结构：商品只保留缝边默认值列（无数量/成本列）、档位无提成、工种按 id 引用。
 */
@SpringBootTest
class CatalogMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void createsCatalogTables() {
        for (var table : new String[]{
                "products", "customers", "employees", "employee_work_types",
                "employee_employment_events", "master_data_change_logs",
                "star_levels", "seam_types", "work_types", "packaging_tiers", "catalog_settings"}) {
            assertThat(tableExists(table)).as("缺少表 " + table).isTrue();
        }
    }

    @Test
    void enforcesBusinessNumberUniquenessAndPrecision() {
        assertThat(columnType("products", "product_no")).isEqualTo("char(6)");
        assertThat(columnType("products", "sale_price")).isEqualTo("decimal(19,4)");
        assertThat(columnType("products", "loss_rate")).isEqualTo("decimal(9,6)");
        assertThat(columnType("products", "version")).isEqualTo("bigint unsigned");
        assertThat(columnExists("products", "created_at")).isTrue();
        assertThat(columnExists("products", "request_id")).isTrue();
        assertThat(columnExists("products", "idempotency_key")).isTrue();
        assertThat(uniqueKeys("products")).contains("uk_products_product_no");
        assertThat(uniqueKeys("customers")).contains("uk_customers_customer_no");
        assertThat(uniqueKeys("employees")).contains("uk_employees_employee_no");
        assertThat(columnType("customers", "customer_no")).isEqualTo("char(6)");
        assertThat(columnType("employees", "employee_no")).isEqualTo("char(6)");
        assertThat(uniqueKeys("employee_work_types")).contains("uk_employee_work_types_employee_type");
        assertThat(uniqueKeys("star_levels")).contains("uk_star_levels_name");
        assertThat(uniqueKeys("catalog_settings")).contains("uk_catalog_settings_key");
    }

    @Test
    void createsStaticDataCatalogTablesWithFixedCategories() {
        assertThat(uniqueKeys("seam_types")).contains("uk_seam_types_name");
        assertThat(columnType("seam_types", "std_minutes")).isEqualTo("int unsigned");
        assertThat(uniqueKeys("work_types")).contains("uk_work_types_code", "uk_work_types_name");
        assertThat(columnExists("work_types", "active")).isFalse();
        assertThat(columnType("employee_work_types", "work_type_id")).isEqualTo("bigint unsigned");
        assertThat(columnExists("employee_work_types", "work_type")).isFalse();
    }

    @Test
    void appliesFinalCatalogStructureAndStaticDataSeeds() {
        // 2026-09-25 压缩为单一基线迁移 V1__yumi_v2_schema.sql，结构与种子同属版本 1
        var baseline = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class);
        assertThat(baseline).isEqualTo(1);

        // 最终结构：星级按 id 引用、商品无缝边列、档位无提成、商品提成自有
        assertThat(columnExists("star_levels", "star")).isFalse();
        assertThat(columnExists("star_levels", "name")).isTrue();
        assertThat(columnExists("products", "star_level_id")).isTrue();
        assertThat(columnExists("products", "star")).isFalse();
        for (var seam : new String[]{
                "seam_minutes", "seam_labor_cost", "seam_default_fee", "seam_reference_fee"}) {
            assertThat(columnExists("products", seam)).as("商品不应再有缝边列 " + seam).isFalse();
        }
        assertThat(columnExists("packaging_tiers", "commission")).isFalse();
        assertThat(columnType("products", "packaging_commission")).isEqualTo("decimal(19,4)");

        // V5 种子：星级五条、四道工序 code 固定
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM star_levels", Integer.class))
                .isGreaterThanOrEqualTo(5);
        assertThat(jdbcTemplate.queryForList("SELECT code FROM work_types", String.class))
                .contains("MAKING", "PACKING_BAG", "SEAM_CUTTING", "OTHER");
    }

    @Test
    void productSeamDefaultsArePointerAndFeeOnly() {
        // 商品缝边默认值：默认缝边剪袋类型（可空＝默认不缝边剪袋）+ 名称与成本单价快照 + 缝边价格
        assertThat(columnType("products", "seam_type_id")).isEqualTo("bigint unsigned");
        assertThat(columnType("products", "seam_type_name")).isEqualTo("varchar(100)");
        assertThat(columnType("products", "seam_std_minutes")).isEqualTo("int unsigned");
        assertThat(columnType("products", "seam_unit_cost")).isEqualTo("decimal(19,4)");
        assertThat(columnType("products", "seam_fee")).isEqualTo("decimal(19,4)");
        assertThat(isNullable("products", "seam_type_id")).isTrue();
        assertThat(isNullable("products", "seam_fee")).isFalse();
        assertThat(foreignKeys("products")).contains("fk_products_seam_type");
        // 商品仍不保存缝边数量与缝边成本
        assertThat(columnExists("products", "seam_minutes")).isFalse();
        assertThat(columnExists("products", "seam_labor_cost")).isFalse();
    }

    @Test
    void seedsSixMinutePackagingTier() {
        // 种子预置 6 分钟档位（真实核算表固定的「6 分钟 × 时薪 ÷ 60 + 商品提成」）；分钟统一为整数
        assertThat(jdbcTemplate.queryForList("SELECT std_minutes FROM packaging_tiers", Integer.class))
                .contains(6);
        assertThat(columnType("packaging_tiers", "std_minutes")).isEqualTo("int unsigned");
    }

    @Test
    void seedsGlobalParametersUsedByLaborFormulas() throws Exception {
        // 时薪与制品有效工时率写在迁移 SQL 的默认值里（取自清库前库内实际值），代码中不硬编码
        var baseline = new String(getClass().getResourceAsStream("/db/migration/V1__yumi_v2_schema.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(baseline).contains("('hourly_wage', 15.000000");
        assertThat(baseline).contains("('workday_hours', 8.000000");
        assertThat(baseline).contains("('making_effective_hour_rate', 0.750000");
        assertThat(baseline).contains("('target_margin_rate', 30.000000");
        assertThat(jdbcTemplate.queryForList("SELECT setting_key FROM catalog_settings", String.class))
                .contains("hourly_wage", "workday_hours", "making_effective_hour_rate", "target_margin_rate");
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Boolean.class, tableName));
    }

    private boolean columnExists(String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) > 0 FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Boolean.class, tableName, columnName));
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                "SELECT column_type FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, tableName, columnName);
    }

    private boolean isNullable(String tableName, String columnName) {
        return "YES".equals(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, tableName, columnName));
    }

    private java.util.List<String> foreignKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND constraint_type = 'FOREIGN KEY'",
                String.class, tableName);
    }

    private java.util.List<String> uniqueKeys(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND non_unique = 0 "
                        + "AND index_name <> 'PRIMARY'",
                String.class, tableName);
    }
}
