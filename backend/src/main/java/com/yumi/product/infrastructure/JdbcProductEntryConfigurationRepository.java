package com.yumi.product.infrastructure;

import com.yumi.product.application.port.ProductEntryConfigurationRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 商品录入配置持久化（MySQL）。 */
@Repository
public class JdbcProductEntryConfigurationRepository implements ProductEntryConfigurationRepository {

    private static final String DEFAULT_KEY = "DEFAULT";

    private final JdbcClient jdbc;

    public JdbcProductEntryConfigurationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DefaultsRecord loadDefaults() {
        return jdbc.sql("""
                        SELECT glue_loss_rate, glue_unit_price, color_paste_unit_price, row_version
                        FROM product_entry_defaults WHERE config_key = :key
                        """)
                .param("key", DEFAULT_KEY)
                .query((resultSet, rowNumber) -> new DefaultsRecord(
                        resultSet.getBigDecimal("glue_loss_rate"),
                        resultSet.getBigDecimal("glue_unit_price"),
                        resultSet.getBigDecimal("color_paste_unit_price"),
                        resultSet.getLong("row_version")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("商品录入默认值尚未初始化"));
    }

    @Override
    public boolean updateDefaults(
            BigDecimal glueLossRate, BigDecimal glueUnitPrice, BigDecimal colorPasteUnitPrice,
            long expectedRowVersion, Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE product_entry_defaults
                        SET glue_loss_rate = :lossRate, glue_unit_price = :gluePrice,
                            color_paste_unit_price = :colorPastePrice,
                            row_version = row_version + 1, updated_at = :now, updated_by = :operatorId
                        WHERE config_key = :key AND row_version = :expectedRowVersion
                        """)
                .param("lossRate", glueLossRate)
                .param("gluePrice", glueUnitPrice)
                .param("colorPastePrice", colorPasteUnitPrice)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("key", DEFAULT_KEY)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public List<StarRecord> loadMakingStars() {
        return jdbc.sql("""
                        SELECT star_code, name, standard_minutes, sort_order, status, row_version
                        FROM product_making_star_options ORDER BY sort_order, star_code
                        """)
                .query((resultSet, rowNumber) -> new StarRecord(
                        resultSet.getString("star_code"),
                        resultSet.getString("name"),
                        resultSet.getInt("standard_minutes"),
                        resultSet.getInt("sort_order"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .list();
    }

    @Override
    public void upsertMakingStar(StarRecord record, Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO product_making_star_options (
                            star_code, name, standard_minutes, sort_order, status, row_version)
                        VALUES (:code, :name, :minutes, :sortOrder, :status, 1)
                        ON DUPLICATE KEY UPDATE
                            name = VALUES(name), standard_minutes = VALUES(standard_minutes),
                            sort_order = VALUES(sort_order), status = VALUES(status),
                            row_version = row_version + 1
                        """)
                .param("code", record.starCode())
                .param("name", record.name())
                .param("minutes", record.standardMinutes())
                .param("sortOrder", record.sortOrder())
                .param("status", record.status())
                .update();
    }

    @Override
    public List<ProcessTimeRecord> loadProcessTimeOptions(String processCode) {
        String sql = """
                SELECT option_business_id, process_code, option_code, name, standard_minutes,
                       sort_order, status, row_version
                FROM product_process_time_options
                """;
        if (processCode != null && !processCode.isBlank()) {
            sql += " WHERE process_code = :processCode";
        }
        sql += " ORDER BY process_code, sort_order, option_code";
        var spec = jdbc.sql(sql);
        if (processCode != null && !processCode.isBlank()) {
            spec = spec.param("processCode", processCode);
        }
        return spec.query((resultSet, rowNumber) -> new ProcessTimeRecord(
                        resultSet.getString("option_business_id"),
                        resultSet.getString("process_code"),
                        resultSet.getString("option_code"),
                        resultSet.getString("name"),
                        resultSet.getInt("standard_minutes"),
                        resultSet.getInt("sort_order"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .list();
    }

    @Override
    public void upsertProcessTimeOption(ProcessTimeRecord record, Instant now, String operatorId) {
        jdbc.sql("""
                        INSERT INTO product_process_time_options (
                            option_business_id, process_code, option_code, name, standard_minutes,
                            sort_order, status, row_version)
                        VALUES (:optionId, :processCode, :optionCode, :name, :minutes, :sortOrder, :status, 1)
                        ON DUPLICATE KEY UPDATE
                            name = VALUES(name), standard_minutes = VALUES(standard_minutes),
                            sort_order = VALUES(sort_order), status = VALUES(status),
                            row_version = row_version + 1
                        """)
                .param("optionId", record.optionBusinessId())
                .param("processCode", record.processCode())
                .param("optionCode", record.optionCode())
                .param("name", record.name())
                .param("minutes", record.standardMinutes())
                .param("sortOrder", record.sortOrder())
                .param("status", record.status())
                .update();
    }
}
