package com.yumi.product.infrastructure;

import com.yumi.product.api.ProductStatus;
import com.yumi.product.application.port.ProductRecord;
import com.yumi.product.application.port.ProductRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 商品持久化（MySQL）：根表 + 三道工序 + 额外单件费用。 */
@Repository
public class JdbcProductRepository implements ProductRepository {

    private final JdbcClient jdbc;

    public JdbcProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProductRecord> findByBusinessId(String productBusinessId) {
        return jdbc.sql(SELECT + " WHERE p.product_business_id = :businessId")
                .param("businessId", productBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public List<ProductRecord> search(String keyword, ProductStatus status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (p.name LIKE :keyword OR p.product_no LIKE :keyword)");
        }
        if (status != null) {
            sql.append(" AND p.status = :status");
        }
        sql.append(" ORDER BY p.product_no, p.id LIMIT :limit OFFSET :offset");
        var spec = jdbc.sql(sql.toString());
        if (keyword != null && !keyword.isBlank()) {
            spec = spec.param("keyword", "%" + keyword.strip() + "%");
        }
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.param("limit", limit).param("offset", offset).query(this::map).list();
    }

    @Override
    public void insert(ProductRecord record) {
        jdbc.sql("""
                        INSERT INTO product_products (
                            product_business_id, product_no, name, description, standard_weight_grams,
                            making_star_code, making_star_name, making_standard_minutes, standard_sale_price,
                            glue_loss_rate, glue_unit_price, color_paste_unit_price, making_commission,
                            mould_count, mould_daily_batches, daily_capacity, status,
                            content_version, row_version, created_at, created_by, updated_at, updated_by)
                        VALUES (:businessId, :productNo, :name, :description, :weight,
                                :starCode, :starName, :starMinutes, :salePrice,
                                :glueLossRate, :gluePrice, :colorPastePrice, :makingCommission,
                                :mouldCount, :mouldBatches, :dailyCapacity, :status,
                                1, 1, :now, :operatorId, :now, :operatorId)
                        """)
                .param("businessId", record.productBusinessId())
                .param("productNo", record.productNo())
                .param("name", record.name())
                .param("description", record.description())
                .param("weight", record.standardWeightGrams())
                .param("starCode", record.makingStarCode())
                .param("starName", record.makingStarName())
                .param("starMinutes", record.makingStandardMinutes())
                .param("salePrice", record.standardSalePrice())
                .param("glueLossRate", record.glueLossRate())
                .param("gluePrice", record.glueUnitPrice())
                .param("colorPastePrice", record.colorPasteUnitPrice())
                .param("makingCommission", record.makingCommission())
                .param("mouldCount", record.mouldCount())
                .param("mouldBatches", record.mouldDailyBatches())
                .param("dailyCapacity", record.dailyCapacity())
                .param("status", record.status().name())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .param("operatorId", record.createdBy())
                .update();
        writeChildTables(record);
    }

    @Override
    public boolean update(ProductRecord record, long expectedRowVersion, Instant now, String operatorId) {
        int updated = jdbc.sql("""
                        UPDATE product_products
                        SET name = :name, description = :description, standard_weight_grams = :weight,
                            making_star_code = :starCode, making_star_name = :starName,
                            making_standard_minutes = :starMinutes, standard_sale_price = :salePrice,
                            glue_loss_rate = :glueLossRate, glue_unit_price = :gluePrice,
                            color_paste_unit_price = :colorPastePrice, making_commission = :makingCommission,
                            mould_count = :mouldCount, mould_daily_batches = :mouldBatches,
                            daily_capacity = :dailyCapacity,
                            content_version = content_version + 1, row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE product_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("name", record.name())
                .param("description", record.description())
                .param("weight", record.standardWeightGrams())
                .param("starCode", record.makingStarCode())
                .param("starName", record.makingStarName())
                .param("starMinutes", record.makingStandardMinutes())
                .param("salePrice", record.standardSalePrice())
                .param("glueLossRate", record.glueLossRate())
                .param("gluePrice", record.glueUnitPrice())
                .param("colorPastePrice", record.colorPasteUnitPrice())
                .param("makingCommission", record.makingCommission())
                .param("mouldCount", record.mouldCount())
                .param("mouldBatches", record.mouldDailyBatches())
                .param("dailyCapacity", record.dailyCapacity())
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("businessId", record.productBusinessId())
                .param("expectedRowVersion", expectedRowVersion)
                .update();
        if (updated != 1) {
            return false;
        }
        writeChildTables(record);
        return true;
    }

    @Override
    public boolean updateStatus(
            String productBusinessId, ProductStatus status, long expectedRowVersion, Instant now,
            String operatorId) {
        return jdbc.sql("""
                        UPDATE product_products
                        SET status = :status, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = :now, updated_by = :operatorId
                        WHERE product_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("status", status.name())
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("businessId", productBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    private void writeChildTables(ProductRecord record) {
        ProductRecord.ProcessCosts costs = record.processCosts();
        upsert("""
                        INSERT INTO product_making_processes (
                            product_business_id, other_material_cost, row_version)
                        VALUES (:businessId, :otherMaterial, 1)
                        ON DUPLICATE KEY UPDATE
                            other_material_cost = VALUES(other_material_cost),
                            row_version = row_version + 1
                        """,
                spec -> spec.param("businessId", record.productBusinessId())
                        .param("otherMaterial", costs.makingOtherMaterialCost()));

        upsert("""
                        INSERT INTO product_bagging_processes (
                            product_business_id, standard_minutes, labor_cost, bag_unit_cost,
                            decoration_unit_price, other_cost, row_version)
                        VALUES (:businessId, :standardMinutes, :labor, :bag, :decoration, :other, 1)
                        ON DUPLICATE KEY UPDATE
                            standard_minutes = VALUES(standard_minutes), labor_cost = VALUES(labor_cost),
                            bag_unit_cost = VALUES(bag_unit_cost),
                            decoration_unit_price = VALUES(decoration_unit_price),
                            other_cost = VALUES(other_cost), row_version = row_version + 1
                        """,
                spec -> spec.param("businessId", record.productBusinessId())
                        .param("standardMinutes", costs.baggingStandardMinutes())
                        .param("labor", costs.baggingLaborCost())
                        .param("bag", costs.baggingBagUnitCost())
                        .param("decoration", costs.baggingDecorationUnitPrice())
                        .param("other", costs.baggingOtherCost()));

        upsert("""
                        INSERT INTO product_seam_cutting_processes (
                            product_business_id, standard_minutes, labor_cost, combined_material_cost,
                            other_cost, row_version)
                        VALUES (:businessId, :standardMinutes, :labor, :material, :other, 1)
                        ON DUPLICATE KEY UPDATE
                            standard_minutes = VALUES(standard_minutes), labor_cost = VALUES(labor_cost),
                            combined_material_cost = VALUES(combined_material_cost),
                            other_cost = VALUES(other_cost), row_version = row_version + 1
                        """,
                spec -> spec.param("businessId", record.productBusinessId())
                        .param("standardMinutes", costs.seamStandardMinutes())
                        .param("labor", costs.seamLaborCost())
                        .param("material", costs.seamCombinedMaterialCost())
                        .param("other", costs.seamOtherCost()));

        upsert("""
                        INSERT INTO product_extra_unit_costs (
                            product_business_id, other_task_labor_cost, transport_packaging_cost,
                            daily_misc_cost, rent_utilities_cost, mould_unit_amortization_cost,
                            other_cost, row_version)
                        VALUES (:businessId, :otherTask, :transport, :daily, :rent, :mould, :other, 1)
                        ON DUPLICATE KEY UPDATE
                            other_task_labor_cost = VALUES(other_task_labor_cost),
                            transport_packaging_cost = VALUES(transport_packaging_cost),
                            daily_misc_cost = VALUES(daily_misc_cost),
                            rent_utilities_cost = VALUES(rent_utilities_cost),
                            mould_unit_amortization_cost = VALUES(mould_unit_amortization_cost),
                            other_cost = VALUES(other_cost), row_version = row_version + 1
                        """,
                spec -> spec.param("businessId", record.productBusinessId())
                        .param("otherTask", costs.extraOtherTaskLaborCost())
                        .param("transport", costs.extraTransportPackagingCost())
                        .param("daily", costs.extraDailyMiscCost())
                        .param("rent", costs.extraRentUtilitiesCost())
                        .param("mould", costs.extraMouldAmortizationCost())
                        .param("other", costs.extraOtherCost()));
    }

    private void upsert(String sql, java.util.function.UnaryOperator<JdbcClient.StatementSpec> binder) {
        binder.apply(jdbc.sql(sql)).update();
    }

    private static final String SELECT = """
            SELECT p.id, p.product_business_id, p.product_no, p.name, p.description,
                   p.standard_weight_grams, p.making_star_code, p.making_star_name,
                   p.making_standard_minutes, p.standard_sale_price, p.glue_loss_rate,
                   p.glue_unit_price, p.color_paste_unit_price, p.making_commission,
                   p.mould_count, p.mould_daily_batches, p.daily_capacity, p.status,
                   p.content_version, p.row_version, p.created_at, p.created_by,
                   p.updated_at, p.updated_by,
                   m.other_material_cost,
                   b.standard_minutes AS bagging_minutes, b.labor_cost AS bagging_labor,
                   b.bag_unit_cost, b.decoration_unit_price, b.other_cost AS bagging_other,
                   s.standard_minutes AS seam_minutes, s.labor_cost AS seam_labor,
                   s.combined_material_cost, s.other_cost AS seam_other,
                   e.other_task_labor_cost, e.transport_packaging_cost, e.daily_misc_cost,
                   e.rent_utilities_cost, e.mould_unit_amortization_cost, e.other_cost AS extra_other
            FROM product_products p
            LEFT JOIN product_making_processes m ON m.product_business_id = p.product_business_id
            LEFT JOIN product_bagging_processes b ON b.product_business_id = p.product_business_id
            LEFT JOIN product_seam_cutting_processes s ON s.product_business_id = p.product_business_id
            LEFT JOIN product_extra_unit_costs e ON e.product_business_id = p.product_business_id
            """;

    private ProductRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProductRecord(
                resultSet.getLong("id"),
                resultSet.getString("product_business_id"),
                resultSet.getString("product_no"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getBigDecimal("standard_weight_grams"),
                resultSet.getString("making_star_code"),
                resultSet.getString("making_star_name"),
                resultSet.getInt("making_standard_minutes"),
                resultSet.getBigDecimal("standard_sale_price"),
                resultSet.getBigDecimal("glue_loss_rate"),
                resultSet.getBigDecimal("glue_unit_price"),
                resultSet.getBigDecimal("color_paste_unit_price"),
                resultSet.getBigDecimal("making_commission"),
                resultSet.getInt("mould_count"),
                resultSet.getInt("mould_daily_batches"),
                resultSet.getInt("daily_capacity"),
                ProductStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                resultSet.getString("created_by"),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)),
                resultSet.getString("updated_by"),
                new ProductRecord.ProcessCosts(
                        resultSet.getBigDecimal("other_material_cost"),
                        resultSet.getInt("bagging_minutes"),
                        resultSet.getBigDecimal("bagging_labor"),
                        resultSet.getBigDecimal("bag_unit_cost"),
                        resultSet.getBigDecimal("decoration_unit_price"),
                        resultSet.getBigDecimal("bagging_other"),
                        resultSet.getInt("seam_minutes"),
                        resultSet.getBigDecimal("seam_labor"),
                        resultSet.getBigDecimal("combined_material_cost"),
                        resultSet.getBigDecimal("seam_other"),
                        resultSet.getBigDecimal("other_task_labor_cost"),
                        resultSet.getBigDecimal("transport_packaging_cost"),
                        resultSet.getBigDecimal("daily_misc_cost"),
                        resultSet.getBigDecimal("rent_utilities_cost"),
                        resultSet.getBigDecimal("mould_unit_amortization_cost"),
                        resultSet.getBigDecimal("extra_other")));
    }
}
