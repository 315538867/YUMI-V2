package com.yumi.product.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.product.application.port.ProductSnapshotRecord;
import com.yumi.product.application.port.ProductSnapshotRepository;
import com.yumi.product.application.port.SchemeSnapshotRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 商品与方案快照持久化（MySQL）；唯一键裁决并发重复创建。 */
@Repository
public class JdbcProductSnapshotRepository implements ProductSnapshotRepository {

    private final JdbcClient jdbc;

    public JdbcProductSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProductSnapshotRecord> findProductSnapshot(String productBusinessId, long sourceContentVersion) {
        return jdbc.sql(PRODUCT_SELECT + """
                         WHERE product_business_id = :businessId
                           AND source_content_version = :sourceContentVersion
                        """)
                .param("businessId", productBusinessId)
                .param("sourceContentVersion", sourceContentVersion)
                .query(JdbcProductSnapshotRepository::mapProduct)
                .optional();
    }

    @Override
    public Optional<ProductSnapshotRecord> findProductSnapshotByBusinessId(String snapshotBusinessId) {
        return jdbc.sql(PRODUCT_SELECT + " WHERE snapshot_business_id = :snapshotId")
                .param("snapshotId", snapshotBusinessId)
                .query(JdbcProductSnapshotRepository::mapProduct)
                .optional();
    }

    @Override
    public long maxProductSnapshotVersion(String productBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(snapshot_version), 0) FROM product_snapshots
                        WHERE product_business_id = :businessId
                        """)
                .param("businessId", productBusinessId)
                .query(Long.class)
                .single();
    }

    @Override
    public void insertProductSnapshot(ProductSnapshotRecord record) {
        try {
            jdbc.sql("""
                            INSERT INTO product_snapshots (
                                snapshot_business_id, product_business_id, snapshot_version,
                                source_content_version, base_payload_json, process_payload_json,
                                extra_cost_payload_json, daily_capacity, content_fingerprint, created_at)
                            VALUES (:snapshotId, :businessId, :snapshotVersion, :sourceContentVersion,
                                    :basePayload, :processPayload, :extraPayload, :dailyCapacity,
                                    :fingerprint, :createdAt)
                            """)
                    .param("snapshotId", record.snapshotBusinessId())
                    .param("businessId", record.productBusinessId())
                    .param("snapshotVersion", record.snapshotVersion())
                    .param("sourceContentVersion", record.sourceContentVersion())
                    .param("basePayload", record.basePayloadJson())
                    .param("processPayload", record.processPayloadJson())
                    .param("extraPayload", record.extraCostPayloadJson())
                    .param("dailyCapacity", record.dailyCapacity())
                    .param("fingerprint", record.contentFingerprint().value())
                    .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new SnapshotConflict(duplicate);
        }
    }

    @Override
    public Optional<SchemeSnapshotRecord> findSchemeSnapshot(
            String schemeBusinessId, long sourceSchemeContentVersion, long sourceProductContentVersion) {
        return jdbc.sql(SCHEME_SELECT + """
                         WHERE scheme_business_id = :schemeId
                           AND source_scheme_content_version = :sourceSchemeVersion
                           AND source_product_content_version = :sourceProductVersion
                        """)
                .param("schemeId", schemeBusinessId)
                .param("sourceSchemeVersion", sourceSchemeContentVersion)
                .param("sourceProductVersion", sourceProductContentVersion)
                .query(JdbcProductSnapshotRepository::mapScheme)
                .optional();
    }

    @Override
    public Optional<SchemeSnapshotRecord> findSchemeSnapshotByBusinessId(String snapshotBusinessId) {
        return jdbc.sql(SCHEME_SELECT + " WHERE snapshot_business_id = :snapshotId")
                .param("snapshotId", snapshotBusinessId)
                .query(JdbcProductSnapshotRepository::mapScheme)
                .optional();
    }

    @Override
    public long maxSchemeSnapshotVersion(String schemeBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(snapshot_version), 0) FROM product_scheme_snapshots
                        WHERE scheme_business_id = :schemeId
                        """)
                .param("schemeId", schemeBusinessId)
                .query(Long.class)
                .single();
    }

    @Override
    public void insertSchemeSnapshot(SchemeSnapshotRecord record) {
        try {
            jdbc.sql("""
                            INSERT INTO product_scheme_snapshots (
                                snapshot_business_id, scheme_business_id, snapshot_version,
                                source_scheme_content_version, source_product_content_version,
                                product_snapshot_business_id, complete_scheme_payload_json,
                                budget_payload_json, content_fingerprint, created_at)
                            VALUES (:snapshotId, :schemeId, :snapshotVersion, :sourceSchemeVersion,
                                    :sourceProductVersion, :productSnapshotId, :schemePayload,
                                    :budgetPayload, :fingerprint, :createdAt)
                            """)
                    .param("snapshotId", record.snapshotBusinessId())
                    .param("schemeId", record.schemeBusinessId())
                    .param("snapshotVersion", record.snapshotVersion())
                    .param("sourceSchemeVersion", record.sourceSchemeContentVersion())
                    .param("sourceProductVersion", record.sourceProductContentVersion())
                    .param("productSnapshotId", record.productSnapshotBusinessId())
                    .param("schemePayload", record.completeSchemePayloadJson())
                    .param("budgetPayload", record.budgetPayloadJson())
                    .param("fingerprint", record.contentFingerprint().value())
                    .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new SnapshotConflict(duplicate);
        }
    }

    /** 并发写入同一快照时的显式信号。 */
    public static final class SnapshotConflict extends RuntimeException {

        public SnapshotConflict(Throwable cause) {
            super("相同内容版本的快照已存在", cause);
        }
    }

    private static final String PRODUCT_SELECT = """
            SELECT snapshot_business_id, product_business_id, snapshot_version, source_content_version,
                   base_payload_json, process_payload_json, extra_cost_payload_json, daily_capacity,
                   content_fingerprint, created_at
            FROM product_snapshots
            """;

    private static final String SCHEME_SELECT = """
            SELECT snapshot_business_id, scheme_business_id, snapshot_version,
                   source_scheme_content_version, source_product_content_version,
                   product_snapshot_business_id, complete_scheme_payload_json, budget_payload_json,
                   content_fingerprint, created_at
            FROM product_scheme_snapshots
            """;

    private static ProductSnapshotRecord mapProduct(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProductSnapshotRecord(
                resultSet.getString("snapshot_business_id"),
                resultSet.getString("product_business_id"),
                resultSet.getLong("snapshot_version"),
                resultSet.getLong("source_content_version"),
                resultSet.getString("base_payload_json"),
                resultSet.getString("process_payload_json"),
                resultSet.getString("extra_cost_payload_json"),
                resultSet.getInt("daily_capacity"),
                ContentFingerprint.of(resultSet.getString("content_fingerprint")),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)));
    }

    private static SchemeSnapshotRecord mapScheme(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SchemeSnapshotRecord(
                resultSet.getString("snapshot_business_id"),
                resultSet.getString("scheme_business_id"),
                resultSet.getLong("snapshot_version"),
                resultSet.getLong("source_scheme_content_version"),
                resultSet.getLong("source_product_content_version"),
                resultSet.getString("product_snapshot_business_id"),
                resultSet.getString("complete_scheme_payload_json"),
                resultSet.getString("budget_payload_json"),
                ContentFingerprint.of(resultSet.getString("content_fingerprint")),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)));
    }
}
