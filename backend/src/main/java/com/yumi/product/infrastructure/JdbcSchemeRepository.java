package com.yumi.product.infrastructure;

import com.yumi.product.api.ProductStatus;
import com.yumi.product.api.SchemeType;
import com.yumi.product.application.port.SchemeRecord;
import com.yumi.product.application.port.SchemeRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 方案持久化（MySQL）：方案根 + 工序同步字段 + 额外费用同步字段。 */
@Repository
public class JdbcSchemeRepository implements SchemeRepository {

    private final JdbcClient jdbc;

    public JdbcSchemeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SchemeRecord> findByBusinessId(String schemeBusinessId) {
        return jdbc.sql(SELECT + " WHERE s.scheme_business_id = :schemeId")
                .param("schemeId", schemeBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<SchemeRecord> findStandardOfProduct(String productBusinessId) {
        return jdbc.sql(SELECT + " WHERE s.product_business_id = :productId AND s.scheme_type = 'SYSTEM_STANDARD'")
                .param("productId", productBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public List<SchemeRecord> findAllOfProduct(String productBusinessId) {
        return jdbc.sql(SELECT + " WHERE s.product_business_id = :productId "
                        + "ORDER BY s.scheme_type DESC, s.scheme_business_id")
                .param("productId", productBusinessId)
                .query(this::map)
                .list();
    }

    @Override
    public void insert(SchemeRecord record) {
        jdbc.sql("""
                        INSERT INTO product_schemes (
                            scheme_business_id, product_business_id, scheme_type, name,
                            standard_weight_grams, target_process, sale_price, glue_source,
                            color_paste_source, status, source_product_content_version,
                            content_version, row_version, created_at, updated_at)
                        VALUES (:schemeId, :productId, :schemeType, :name, :weight, :targetProcess,
                                :salePrice, :glueSource, :colorPasteSource, :status,
                                :sourceProductVersion, 1, 1, :now, :now)
                        """)
                .param("schemeId", record.schemeBusinessId())
                .param("productId", record.productBusinessId())
                .param("schemeType", record.schemeType().name())
                .param("name", record.name())
                .param("weight", record.standardWeightGrams())
                .param("targetProcess", record.targetProcess())
                .param("salePrice", record.salePrice())
                .param("glueSource", record.glueSource())
                .param("colorPasteSource", record.colorPasteSource())
                .param("status", record.status().name())
                .param("sourceProductVersion", record.sourceProductContentVersion())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .update();
        writeChildTables(record);
    }

    @Override
    public boolean update(SchemeRecord record, long expectedRowVersion, Instant now) {
        int updated = jdbc.sql("""
                        UPDATE product_schemes
                        SET name = :name, standard_weight_grams = :weight, target_process = :targetProcess,
                            sale_price = :salePrice, glue_source = :glueSource,
                            color_paste_source = :colorPasteSource,
                            source_product_content_version = :sourceProductVersion,
                            content_version = content_version + 1, row_version = row_version + 1,
                            updated_at = :now
                        WHERE scheme_business_id = :schemeId AND row_version = :expectedRowVersion
                        """)
                .param("name", record.name())
                .param("weight", record.standardWeightGrams())
                .param("targetProcess", record.targetProcess())
                .param("salePrice", record.salePrice())
                .param("glueSource", record.glueSource())
                .param("colorPasteSource", record.colorPasteSource())
                .param("sourceProductVersion", record.sourceProductContentVersion())
                .param("now", JdbcTimes.toUtc(now))
                .param("schemeId", record.schemeBusinessId())
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
            String schemeBusinessId, String status, long expectedRowVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE product_schemes
                        SET status = :status, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = :now
                        WHERE scheme_business_id = :schemeId AND row_version = :expectedRowVersion
                        """)
                .param("status", status)
                .param("now", JdbcTimes.toUtc(now))
                .param("schemeId", schemeBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    private void writeChildTables(SchemeRecord record) {
        SchemeRecord.SchemeCosts costs = record.costs();
        jdbc.sql("""
                        INSERT INTO product_scheme_making (
                            scheme_business_id, standard_minutes, making_commission,
                            other_material_cost, row_version)
                        VALUES (:schemeId, :standardMinutes, :commission, :otherMaterial, 1)
                        ON DUPLICATE KEY UPDATE
                            standard_minutes = VALUES(standard_minutes),
                            making_commission = VALUES(making_commission),
                            other_material_cost = VALUES(other_material_cost),
                            row_version = row_version + 1
                        """)
                .param("schemeId", record.schemeBusinessId())
                .param("standardMinutes", costs.makingStandardMinutes())
                .param("commission", costs.makingCommission())
                .param("otherMaterial", costs.makingOtherMaterialCost())
                .update();

        jdbc.sql("""
                        INSERT INTO product_scheme_bagging (
                            scheme_business_id, standard_minutes, labor_cost, bag_unit_cost, bag_source,
                            decoration_unit_price, decoration_source, other_cost, row_version)
                        VALUES (:schemeId, :standardMinutes, :labor, :bag, :bagSource, :decoration,
                                :decorationSource, :other, 1)
                        ON DUPLICATE KEY UPDATE
                            standard_minutes = VALUES(standard_minutes), labor_cost = VALUES(labor_cost),
                            bag_unit_cost = VALUES(bag_unit_cost), bag_source = VALUES(bag_source),
                            decoration_unit_price = VALUES(decoration_unit_price),
                            decoration_source = VALUES(decoration_source),
                            other_cost = VALUES(other_cost), row_version = row_version + 1
                        """)
                .param("schemeId", record.schemeBusinessId())
                .param("standardMinutes", costs.baggingStandardMinutes())
                .param("labor", costs.baggingLaborCost())
                .param("bag", costs.baggingBagUnitCost())
                .param("bagSource", costs.baggingBagSource())
                .param("decoration", costs.baggingDecorationUnitPrice())
                .param("decorationSource", costs.baggingDecorationSource())
                .param("other", costs.baggingOtherCost())
                .update();

        jdbc.sql("""
                        INSERT INTO product_scheme_seam_cutting (
                            scheme_business_id, standard_minutes, labor_cost,
                            combined_material_cost, other_cost, row_version)
                        VALUES (:schemeId, :standardMinutes, :labor, :material, :other, 1)
                        ON DUPLICATE KEY UPDATE
                            standard_minutes = VALUES(standard_minutes), labor_cost = VALUES(labor_cost),
                            combined_material_cost = VALUES(combined_material_cost),
                            other_cost = VALUES(other_cost), row_version = row_version + 1
                        """)
                .param("schemeId", record.schemeBusinessId())
                .param("standardMinutes", costs.seamStandardMinutes())
                .param("labor", costs.seamLaborCost())
                .param("material", costs.seamCombinedMaterialCost())
                .param("other", costs.seamOtherCost())
                .update();

        jdbc.sql("""
                        INSERT INTO product_scheme_extra_unit_costs (
                            scheme_business_id, other_task_labor_cost, transport_packaging_cost,
                            daily_misc_cost, rent_utilities_cost, mould_unit_amortization_cost,
                            other_cost, row_version)
                        VALUES (:schemeId, :otherTask, :transport, :daily, :rent, :mould, :other, 1)
                        ON DUPLICATE KEY UPDATE
                            other_task_labor_cost = VALUES(other_task_labor_cost),
                            transport_packaging_cost = VALUES(transport_packaging_cost),
                            daily_misc_cost = VALUES(daily_misc_cost),
                            rent_utilities_cost = VALUES(rent_utilities_cost),
                            mould_unit_amortization_cost = VALUES(mould_unit_amortization_cost),
                            other_cost = VALUES(other_cost), row_version = row_version + 1
                        """)
                .param("schemeId", record.schemeBusinessId())
                .param("otherTask", costs.extraOtherTaskLaborCost())
                .param("transport", costs.extraTransportPackagingCost())
                .param("daily", costs.extraDailyMiscCost())
                .param("rent", costs.extraRentUtilitiesCost())
                .param("mould", costs.extraMouldAmortizationCost())
                .param("other", costs.extraOtherCost())
                .update();
    }

    private static final String SELECT = """
            SELECT s.id, s.scheme_business_id, s.product_business_id, s.scheme_type, s.name,
                   s.standard_weight_grams, s.target_process, s.sale_price, s.glue_source,
                   s.color_paste_source, s.status, s.source_product_content_version,
                   s.content_version, s.row_version, s.created_at, s.updated_at,
                   m.standard_minutes AS making_minutes, m.making_commission,
                   m.other_material_cost,
                   b.standard_minutes AS bagging_minutes, b.labor_cost AS bagging_labor,
                   b.bag_unit_cost, b.bag_source, b.decoration_unit_price, b.decoration_source,
                   b.other_cost AS bagging_other,
                   c.standard_minutes AS seam_minutes, c.labor_cost AS seam_labor,
                   c.combined_material_cost, c.other_cost AS seam_other,
                   e.other_task_labor_cost, e.transport_packaging_cost, e.daily_misc_cost,
                   e.rent_utilities_cost, e.mould_unit_amortization_cost, e.other_cost AS extra_other
            FROM product_schemes s
            LEFT JOIN product_scheme_making m ON m.scheme_business_id = s.scheme_business_id
            LEFT JOIN product_scheme_bagging b ON b.scheme_business_id = s.scheme_business_id
            LEFT JOIN product_scheme_seam_cutting c ON c.scheme_business_id = s.scheme_business_id
            LEFT JOIN product_scheme_extra_unit_costs e ON e.scheme_business_id = s.scheme_business_id
            """;

    private SchemeRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SchemeRecord(
                resultSet.getLong("id"),
                resultSet.getString("scheme_business_id"),
                resultSet.getString("product_business_id"),
                SchemeType.valueOf(resultSet.getString("scheme_type")),
                resultSet.getString("name"),
                resultSet.getBigDecimal("standard_weight_grams"),
                resultSet.getString("target_process"),
                resultSet.getBigDecimal("sale_price"),
                resultSet.getString("glue_source"),
                resultSet.getString("color_paste_source"),
                ProductStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("source_product_content_version"),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)),
                new SchemeRecord.SchemeCosts(
                        resultSet.getInt("making_minutes"),
                        resultSet.getBigDecimal("making_commission"),
                        resultSet.getBigDecimal("other_material_cost"),
                        resultSet.getInt("bagging_minutes"),
                        resultSet.getBigDecimal("bagging_labor"),
                        resultSet.getBigDecimal("bag_unit_cost"),
                        resultSet.getString("bag_source"),
                        resultSet.getBigDecimal("decoration_unit_price"),
                        resultSet.getString("decoration_source"),
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
