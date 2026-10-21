package com.yumi.customer.infrastructure;

import com.yumi.customer.application.port.CustomerSnapshotRecord;
import com.yumi.customer.application.port.CustomerSnapshotRepository;
import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 客户快照持久化（MySQL）。 */
@Repository
public class JdbcCustomerSnapshotRepository implements CustomerSnapshotRepository {

    private final JdbcClient jdbc;

    public JdbcCustomerSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CustomerSnapshotRecord> find(
            String customerBusinessId, long sourceContentVersion, ContentFingerprint overrideFingerprint) {
        return jdbc.sql(SELECT + """
                         WHERE customer_business_id = :businessId
                           AND source_content_version = :sourceContentVersion
                           AND override_fingerprint = :overrideFingerprint
                        """)
                .param("businessId", customerBusinessId)
                .param("sourceContentVersion", sourceContentVersion)
                .param("overrideFingerprint", overrideFingerprint.value())
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<CustomerSnapshotRecord> findByBusinessId(String snapshotBusinessId) {
        return jdbc.sql(SELECT + " WHERE snapshot_business_id = :snapshotId")
                .param("snapshotId", snapshotBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public void insert(CustomerSnapshotRecord record) {
        try {
            jdbc.sql("""
                            INSERT INTO customer_snapshots (
                                snapshot_business_id, customer_business_id, snapshot_version,
                                source_content_version, override_fingerprint, customer_no, name,
                                contact_name, phone, address, content_fingerprint, created_at)
                            VALUES (:snapshotId, :businessId, :snapshotVersion, :sourceContentVersion,
                                    :overrideFingerprint, :customerNo, :name, :contactName, :phone,
                                    :address, :contentFingerprint, :createdAt)
                            """)
                    .param("snapshotId", record.snapshotBusinessId())
                    .param("businessId", record.customerBusinessId())
                    .param("snapshotVersion", record.snapshotVersion())
                    .param("sourceContentVersion", record.sourceContentVersion())
                    .param("overrideFingerprint", record.overrideFingerprint().value())
                    .param("customerNo", record.customerNo())
                    .param("name", record.name())
                    .param("contactName", record.contactName())
                    .param("phone", record.phone())
                    .param("address", record.address())
                    .param("contentFingerprint", record.contentFingerprint().value())
                    .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            // 并发取得同一快照：唯一键是权威裁决者，由调用方重新读取既有记录
            throw new SnapshotAlreadyExists(duplicate);
        }
    }

    @Override
    public long maxSnapshotVersion(String customerBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(snapshot_version), 0) FROM customer_snapshots
                        WHERE customer_business_id = :businessId
                        """)
                .param("businessId", customerBusinessId)
                .query(Long.class)
                .single();
    }

    @Override
    public List<CustomerSnapshotRecord> findAllOfCustomer(String customerBusinessId) {
        return jdbc.sql(SELECT + " WHERE customer_business_id = :businessId ORDER BY snapshot_version")
                .param("businessId", customerBusinessId)
                .query(this::map)
                .list();
    }

    /** 并发写入同一快照时的显式信号。 */
    public static final class SnapshotAlreadyExists extends RuntimeException {

        public SnapshotAlreadyExists(Throwable cause) {
            super("相同内容版本与覆盖指纹的快照已存在", cause);
        }
    }

    private static final String SELECT = """
            SELECT id, snapshot_business_id, customer_business_id, snapshot_version,
                   source_content_version, override_fingerprint, customer_no, name, contact_name,
                   phone, address, content_fingerprint, created_at
            FROM customer_snapshots
            """;

    private CustomerSnapshotRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CustomerSnapshotRecord(
                resultSet.getLong("id"),
                resultSet.getString("snapshot_business_id"),
                resultSet.getString("customer_business_id"),
                resultSet.getLong("snapshot_version"),
                resultSet.getLong("source_content_version"),
                ContentFingerprint.of(resultSet.getString("override_fingerprint")),
                resultSet.getString("customer_no"),
                resultSet.getString("name"),
                resultSet.getString("contact_name"),
                resultSet.getString("phone"),
                resultSet.getString("address"),
                ContentFingerprint.of(resultSet.getString("content_fingerprint")),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)));
    }
}
