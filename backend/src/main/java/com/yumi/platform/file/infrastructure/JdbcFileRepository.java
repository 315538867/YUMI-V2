package com.yumi.platform.file.infrastructure;

import com.yumi.platform.api.file.FileStatus;
import com.yumi.platform.file.application.port.FileRecord;
import com.yumi.platform.file.application.port.FileReferenceRecord;
import com.yumi.platform.file.application.port.FileRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 文件与引用持久化（MySQL）。 */
@Repository
public class JdbcFileRepository implements FileRepository {

    private final JdbcClient jdbc;

    public JdbcFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertFile(FileRecord record) {
        jdbc.sql("""
                        INSERT INTO platform_files (
                            file_business_id, storage_key, original_name, media_type, size_bytes,
                            sha256, status, row_version, created_at, created_by, updated_at, updated_by)
                        VALUES (:fileBusinessId, :storageKey, :originalName, :mediaType, :sizeBytes,
                                :sha256, :status, 1, :now, :operatorId, :now, :operatorId)
                        """)
                .param("fileBusinessId", record.fileBusinessId())
                .param("storageKey", record.storageKey())
                .param("originalName", record.originalName())
                .param("mediaType", record.mediaType())
                .param("sizeBytes", record.sizeBytes())
                .param("sha256", record.sha256())
                .param("status", record.status().name())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .param("operatorId", record.createdBy())
                .update();
    }

    @Override
    public Optional<FileRecord> findFile(String fileBusinessId) {
        return jdbc.sql("""
                        SELECT id, file_business_id, storage_key, original_name, media_type, size_bytes,
                               sha256, status, row_version, created_at, created_by, updated_at, updated_by
                        FROM platform_files WHERE file_business_id = :fileBusinessId
                        """)
                .param("fileBusinessId", fileBusinessId)
                .query(this::mapFile)
                .optional();
    }

    @Override
    public boolean updateStatus(
            String fileBusinessId, FileStatus status, long expectedRowVersion, Instant now,
            String operatorId) {
        return jdbc.sql("""
                        UPDATE platform_files
                        SET status = :status, row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE file_business_id = :fileBusinessId AND row_version = :expectedRowVersion
                        """)
                .param("status", status.name())
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("fileBusinessId", fileBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public void insertReference(FileReferenceRecord record) {
        jdbc.sql("""
                        INSERT INTO platform_file_references (
                            reference_business_id, file_business_id, owner_module, owner_type,
                            owner_business_id, purpose_code, created_at, created_by)
                        VALUES (:referenceBusinessId, :fileBusinessId, :ownerModule, :ownerType,
                                :ownerBusinessId, :purposeCode, :now, :operatorId)
                        """)
                .param("referenceBusinessId", record.referenceBusinessId())
                .param("fileBusinessId", record.fileBusinessId())
                .param("ownerModule", record.ownerModule())
                .param("ownerType", record.ownerType())
                .param("ownerBusinessId", record.ownerBusinessId())
                .param("purposeCode", record.purposeCode())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .param("operatorId", record.createdBy())
                .update();
    }

    @Override
    public Optional<FileReferenceRecord> findReference(
            String fileBusinessId, String ownerModule, String ownerType, String ownerBusinessId,
            String purposeCode) {
        return jdbc.sql(SELECT_REFERENCE + """
                         WHERE file_business_id = :fileBusinessId AND owner_module = :ownerModule
                           AND owner_type = :ownerType AND owner_business_id = :ownerBusinessId
                           AND purpose_code = :purposeCode
                        """)
                .param("fileBusinessId", fileBusinessId)
                .param("ownerModule", ownerModule)
                .param("ownerType", ownerType)
                .param("ownerBusinessId", ownerBusinessId)
                .param("purposeCode", purposeCode)
                .query(this::mapReference)
                .optional();
    }

    @Override
    public boolean deleteReference(String referenceBusinessId) {
        return jdbc.sql("DELETE FROM platform_file_references WHERE reference_business_id = :referenceBusinessId")
                .param("referenceBusinessId", referenceBusinessId)
                .update() == 1;
    }

    @Override
    public List<FileReferenceRecord> findReferencesOfFile(String fileBusinessId) {
        return jdbc.sql(SELECT_REFERENCE + " WHERE file_business_id = :fileBusinessId ORDER BY id")
                .param("fileBusinessId", fileBusinessId)
                .query(this::mapReference)
                .list();
    }

    @Override
    public int countReferences(String fileBusinessId) {
        return jdbc.sql("SELECT COUNT(*) FROM platform_file_references WHERE file_business_id = :fileBusinessId")
                .param("fileBusinessId", fileBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public boolean markDeleted(String fileBusinessId, long expectedRowVersion, Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE platform_files
                        SET status = 'DELETED', row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE file_business_id = :fileBusinessId AND row_version = :expectedRowVersion
                          AND status <> 'DELETED'
                        """)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("fileBusinessId", fileBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    private static final String SELECT_REFERENCE = """
            SELECT id, reference_business_id, file_business_id, owner_module, owner_type,
                   owner_business_id, purpose_code, created_at, created_by
            FROM platform_file_references
            """;

    private FileRecord mapFile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FileRecord(
                resultSet.getLong("id"),
                resultSet.getString("file_business_id"),
                resultSet.getString("storage_key"),
                resultSet.getString("original_name"),
                resultSet.getString("media_type"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("sha256"),
                FileStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                resultSet.getString("created_by"),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)),
                resultSet.getString("updated_by"));
    }

    private FileReferenceRecord mapReference(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FileReferenceRecord(
                resultSet.getLong("id"),
                resultSet.getString("reference_business_id"),
                resultSet.getString("file_business_id"),
                resultSet.getString("owner_module"),
                resultSet.getString("owner_type"),
                resultSet.getString("owner_business_id"),
                resultSet.getString("purpose_code"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                resultSet.getString("created_by"));
    }
}
