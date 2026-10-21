package com.yumi.reporting.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.reporting.application.ReportingRebuildRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 报表影子版本持久化（MySQL）。 */
@Repository
public class JdbcReportingRebuildRepository implements ReportingRebuildRepository {

    private final JdbcClient jdbc;

    public JdbcReportingRebuildRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertVersion(String versionBusinessId, String status, java.time.Instant builtAt) {
        jdbc.sql("""
                        INSERT INTO reporting_projection_versions (
                            version_business_id, status, record_count, difference_count, built_at)
                        VALUES (:id, :status, 0, 0, :builtAt)
                        """)
                .param("id", versionBusinessId)
                .param("status", status)
                .param("builtAt", JdbcTimes.toUtc(builtAt))
                .update();
    }

    @Override
    public void copyLiveRecords(String versionBusinessId) {
        jdbc.sql("""
                        INSERT INTO reporting_projection_version_records (
                            version_business_id, feed_code, source_business_id, source_version,
                            payload_json, occurred_at)
                        SELECT :id, feed_code, source_business_id, source_version, payload_json,
                               occurred_at
                        FROM reporting_projection_records
                        """).param("id", versionBusinessId).update();
    }

    @Override
    public long countLiveRecords(String feedCode) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM reporting_projection_records WHERE feed_code = :feed
                        """).param("feed", feedCode).query(Long.class).single();
    }

    @Override
    public long countVersionRecords(String versionBusinessId, String feedCode) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM reporting_projection_version_records
                        WHERE version_business_id = :id AND feed_code = :feed
                        """).param("id", versionBusinessId).param("feed", feedCode)
                .query(Long.class).single();
    }

    @Override
    public Long maxLiveSourceVersion(String feedCode) {
        return jdbc.sql("""
                        SELECT MAX(source_version) FROM reporting_projection_records
                        WHERE feed_code = :feed
                        """).param("feed", feedCode).query(Long.class).optional().orElse(null);
    }

    @Override
    public Long maxVersionSourceVersion(String versionBusinessId, String feedCode) {
        return jdbc.sql("""
                        SELECT MAX(source_version) FROM reporting_projection_version_records
                        WHERE version_business_id = :id AND feed_code = :feed
                        """).param("id", versionBusinessId).param("feed", feedCode)
                .query(Long.class).optional().orElse(null);
    }

    @Override
    public long countVersionRecords(String versionBusinessId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM reporting_projection_version_records
                        WHERE version_business_id = :id
                        """).param("id", versionBusinessId).query(Long.class).single();
    }

    @Override
    public String activeVersionId() {
        return jdbc.sql("""
                        SELECT version_business_id FROM reporting_projection_active
                        WHERE singleton_id = 1
                        """).query(String.class).optional().orElse(null);
    }

    @Override
    public void retireActiveVersions(java.time.Instant retiredAt) {
        jdbc.sql("""
                        UPDATE reporting_projection_versions SET status = 'RETIRED'
                        WHERE status = 'ACTIVE'
                        """).update();
    }

    @Override
    public void activateVersion(
            String versionBusinessId, long recordCount, int differenceCount,
            java.time.Instant activatedAt) {
        jdbc.sql("""
                        UPDATE reporting_projection_versions
                        SET status = 'ACTIVE', record_count = :recordCount,
                            difference_count = :differenceCount, activated_at = :activatedAt
                        WHERE version_business_id = :id
                        """)
                .param("recordCount", recordCount)
                .param("differenceCount", differenceCount)
                .param("activatedAt", JdbcTimes.toUtc(activatedAt))
                .param("id", versionBusinessId)
                .update();
    }

    @Override
    public void markFailed(String versionBusinessId, int differenceCount) {
        jdbc.sql("""
                        UPDATE reporting_projection_versions
                        SET status = 'FAILED', difference_count = :differenceCount
                        WHERE version_business_id = :id
                        """).param("differenceCount", differenceCount).param("id", versionBusinessId)
                .update();
    }

    @Override
    public void setActivePointer(String versionBusinessId) {
        jdbc.sql("""
                        INSERT INTO reporting_projection_active (singleton_id, version_business_id)
                        VALUES (1, :id)
                        ON DUPLICATE KEY UPDATE version_business_id = VALUES(version_business_id)
                        """).param("id", versionBusinessId).update();
    }

    @Override
    public void insertDifference(
            String differenceBusinessId, String versionBusinessId, String feedCode, String detail) {
        jdbc.sql("""
                        INSERT INTO reporting_projection_differences (
                            difference_business_id, version_business_id, feed_code, detail_text,
                            created_at)
                        VALUES (:id, :versionId, :feed, :detail, UTC_TIMESTAMP(6))
                        """)
                .param("id", differenceBusinessId)
                .param("versionId", versionBusinessId)
                .param("feed", feedCode)
                .param("detail", detail)
                .update();
    }

    @Override
    public List<VersionRecord> listVersions() {
        return jdbc.sql("""
                        SELECT version_business_id, status, record_count, difference_count, built_at,
                               activated_at
                        FROM reporting_projection_versions ORDER BY built_at DESC
                        """)
                .query((resultSet, rowNumber) -> new VersionRecord(
                        resultSet.getString("version_business_id"),
                        resultSet.getString("status"),
                        resultSet.getLong("record_count"),
                        resultSet.getInt("difference_count"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "built_at", java.time.LocalDateTime.class)),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "activated_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public List<DifferenceRecord> listDifferences(String versionBusinessId) {
        return jdbc.sql("""
                        SELECT difference_business_id, feed_code, detail_text
                        FROM reporting_projection_differences
                        WHERE version_business_id = :id
                        """).param("id", versionBusinessId)
                .query((resultSet, rowNumber) -> new DifferenceRecord(
                        resultSet.getString("difference_business_id"),
                        resultSet.getString("feed_code"),
                        resultSet.getString("detail_text")))
                .list();
    }
}
