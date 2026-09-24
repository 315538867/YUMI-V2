package com.yumi.catalog.product.internal;

import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * products 表访问：显式列读写（不引入 JPA 实体，沿用库内 JdbcTemplate 风格）；
 * 更新带乐观版本条件，失配映射为 CONFLICT_VERSION。
 */
@Repository
public class ProductRepository {

    private static final String COLUMNS = """
            id, product_no, name, note, status, image_file_id, star_level_id, star_name, star_std_minutes,
            sale_price, weight_g, loss_rate, glue_unit_price, glue_grams, glue_cost,
            colorpaste_unit_price, colorpaste_cost, qty_8h, qty_6h, product_labor_fee,
            packaging_tier_id, packaging_tier_name, packaging_std_minutes, packaging_commission,
            packaging_labor_fee, seam_type_id, seam_type_name, seam_type_cost_price, seam_fee,
            box_labor_fee, transport_packing_fee, daily_sundries_fee, rent_utilities_fee,
            mold_amort_fee, material_cost, labor_cost, other_cost, total_cost, reference_price, version
            """;

    private static final RowMapper<ProductRow> MAPPER = ProductRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public ProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ProductRow> findById(long id) {
        var rows = jdbcTemplate.query("SELECT " + COLUMNS + " FROM products WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<ProductRow> find(String status, String name) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM products WHERE 1=1");
        var args = new java.util.ArrayList<Object>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        if (name != null && !name.isBlank()) {
            sql.append(" AND name LIKE CONCAT('%', ?, '%')");
            args.add(name.trim());
        }
        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public long insert(ProductRow row, String requestId, String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO products (
                    product_no, name, note, status, image_file_id, star_level_id, star_name, star_std_minutes,
                    sale_price, weight_g, loss_rate, glue_unit_price, glue_grams, glue_cost,
                    colorpaste_unit_price, colorpaste_cost, qty_8h, qty_6h, product_labor_fee,
                    packaging_tier_id, packaging_tier_name, packaging_std_minutes, packaging_commission,
                    packaging_labor_fee, seam_type_id, seam_type_name, seam_type_cost_price, seam_fee,
                    box_labor_fee, transport_packing_fee, daily_sundries_fee, rent_utilities_fee,
                    mold_amort_fee, material_cost, labor_cost, other_cost, total_cost, reference_price,
                    version, created_at, updated_at, request_id, idempotency_key
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
                """,
                row.productNo(), row.name(), row.note(), row.status(), row.imageFileId(),
                row.starLevelId(), row.starName(), row.starStdMinutes(),
                row.salePrice(), row.weightG(), row.lossRate(),
                row.glueUnitPrice(), row.glueGrams(), row.glueCost(),
                row.colorpasteUnitPrice(), row.colorpasteCost(),
                row.qty8h(), row.qty6h(), row.productLaborFee(),
                row.packagingTierId(), row.packagingTierName(), row.packagingStdMinutes(),
                row.packagingCommission(), row.packagingLaborFee(),
                row.seamTypeId(), row.seamTypeName(), row.seamTypeCostPrice(), row.seamFee(),
                row.boxLaborFee(), row.transportPackingFee(), row.dailySundriesFee(),
                row.rentUtilitiesFee(), row.moldAmortFee(),
                row.materialCost(), row.laborCost(), row.otherCost(), row.totalCost(), row.referencePrice(),
                requestId, idempotencyKey);
        var id = jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE product_no = ?", Long.class, row.productNo());
        return id;
    }

    /** 按旧版本整体覆盖业务列并递增版本；版本失配 → CONFLICT_VERSION。 */
    public void update(ProductRow row, String requestId) {
        int updated = jdbcTemplate.update("""
                UPDATE products SET
                    name = ?, note = ?, status = ?, image_file_id = ?, star_level_id = ?, star_name = ?,
                    star_std_minutes = ?, sale_price = ?, weight_g = ?, loss_rate = ?,
                    glue_unit_price = ?, glue_grams = ?, glue_cost = ?,
                    colorpaste_unit_price = ?, colorpaste_cost = ?,
                    qty_8h = ?, qty_6h = ?, product_labor_fee = ?,
                    packaging_tier_id = ?, packaging_tier_name = ?, packaging_std_minutes = ?,
                    packaging_commission = ?, packaging_labor_fee = ?,
                    seam_type_id = ?, seam_type_name = ?, seam_type_cost_price = ?, seam_fee = ?,
                    box_labor_fee = ?, transport_packing_fee = ?, daily_sundries_fee = ?,
                    rent_utilities_fee = ?, mold_amort_fee = ?,
                    material_cost = ?, labor_cost = ?, other_cost = ?, total_cost = ?, reference_price = ?,
                    version = version + 1, updated_at = UTC_TIMESTAMP(6), request_id = ?
                WHERE id = ? AND version = ?
                """,
                row.name(), row.note(), row.status(), row.imageFileId(), row.starLevelId(), row.starName(),
                row.starStdMinutes(), row.salePrice(), row.weightG(), row.lossRate(),
                row.glueUnitPrice(), row.glueGrams(), row.glueCost(),
                row.colorpasteUnitPrice(), row.colorpasteCost(),
                row.qty8h(), row.qty6h(), row.productLaborFee(),
                row.packagingTierId(), row.packagingTierName(), row.packagingStdMinutes(),
                row.packagingCommission(), row.packagingLaborFee(),
                row.seamTypeId(), row.seamTypeName(), row.seamTypeCostPrice(), row.seamFee(),
                row.boxLaborFee(), row.transportPackingFee(), row.dailySundriesFee(),
                row.rentUtilitiesFee(), row.moldAmortFee(),
                row.materialCost(), row.laborCost(), row.otherCost(), row.totalCost(), row.referencePrice(),
                requestId, row.id(), row.version());
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
    }

    private static ProductRow map(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        long tierId = rs.getLong("packaging_tier_id");
        boolean tierNull = rs.wasNull();
        long seamTypeId = rs.getLong("seam_type_id");
        boolean seamTypeNull = rs.wasNull();
        long fileId = rs.getLong("image_file_id");
        boolean fileNull = rs.wasNull();
        return new ProductRow(
                id,
                rs.getString("product_no"),
                rs.getString("name"),
                rs.getString("note"),
                rs.getString("status"),
                fileNull ? null : fileId,
                rs.getLong("star_level_id"),
                rs.getString("star_name"),
                rs.getInt("star_std_minutes"),
                rs.getBigDecimal("sale_price"),
                rs.getInt("weight_g"),
                rs.getBigDecimal("loss_rate"),
                rs.getBigDecimal("glue_unit_price"),
                rs.getInt("glue_grams"),
                rs.getBigDecimal("glue_cost"),
                rs.getBigDecimal("colorpaste_unit_price"),
                rs.getBigDecimal("colorpaste_cost"),
                rs.getInt("qty_8h"),
                rs.getInt("qty_6h"),
                rs.getBigDecimal("product_labor_fee"),
                tierNull ? null : tierId,
                rs.getString("packaging_tier_name"),
                rs.getBigDecimal("packaging_std_minutes"),
                rs.getBigDecimal("packaging_commission"),
                rs.getBigDecimal("packaging_labor_fee"),
                seamTypeNull ? null : seamTypeId,
                rs.getString("seam_type_name"),
                rs.getBigDecimal("seam_type_cost_price"),
                rs.getBigDecimal("seam_fee"),
                rs.getBigDecimal("box_labor_fee"),
                rs.getBigDecimal("transport_packing_fee"),
                rs.getBigDecimal("daily_sundries_fee"),
                rs.getBigDecimal("rent_utilities_fee"),
                rs.getBigDecimal("mold_amort_fee"),
                rs.getBigDecimal("material_cost"),
                rs.getBigDecimal("labor_cost"),
                rs.getBigDecimal("other_cost"),
                rs.getBigDecimal("total_cost"),
                rs.getBigDecimal("reference_price"),
                rs.getLong("version"));
    }
}
