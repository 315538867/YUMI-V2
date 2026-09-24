package com.yumi.files.api;

import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 文件存储：MIME 白名单与 5MB 上限校验、sha256 幂等去重、内容按 sha256 落盘、元数据进 file_metadata。
 */
@Service
public class FileStorageService {

    /** 派生决策：仅允许三种图片 MIME（任务 2.4 冻结口径）。 */
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    /** 派生决策：单文件上限 5MB（任务 2.4 派生，超过按校验失败处理）。 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    /** 派生决策：内容按 sha256 寻址存储于 ${YUMI_FILES_DIR:/tmp/yumi-files}/<sha256>。 */
    private final Path root;
    private final JdbcTemplate jdbcTemplate;

    public FileStorageService(JdbcTemplate jdbcTemplate,
                              @Value("${YUMI_FILES_DIR:/tmp/yumi-files}") String filesDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.root = Path.of(filesDir);
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new IllegalStateException("文件存储目录创建失败: " + root, e);
        }
    }

    /** 元数据行（GET 用）。 */
    public record FileMetadata(long id, String objectKey, String contentType, long contentLength, String sha256) {
    }

    public FileResponse store(String originalFilename, String contentType, byte[] bytes,
                              String requestId, String idempotencyKey) {
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)
                || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            var message = "文件必须为 jpg/png/webp 图片且不超过 5MB";
            throw new ApiException(ErrorCode.VALIDATION_INVALID, message, List.of(new ApiFieldError("file", message)));
        }
        var sha = sha256(bytes);
        var existing = findBySha(sha);
        if (existing != null) {
            return toResponse(existing);
        }
        try {
            Files.write(root.resolve(sha), bytes);
        } catch (Exception e) {
            throw new IllegalStateException("文件写入失败", e);
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO file_metadata (object_key, original_filename, content_type, content_length, "
                            + "sha256, created_at, updated_at, request_id, idempotency_key) "
                            + "VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)",
                    sha, safeFilename(originalFilename), contentType, bytes.length, sha, requestId, idempotencyKey);
        } catch (DuplicateKeyException race) {
            var winner = findBySha(sha);
            if (winner != null) {
                return toResponse(winner);
            }
            throw race;
        }
        var stored = findBySha(sha);
        return toResponse(stored);
    }

    public FileMetadata getMetadata(long id) {
        var rows = jdbcTemplate.query(
                "SELECT id, object_key, content_type, content_length, sha256 FROM file_metadata WHERE id = ?",
                (rs, i) -> new FileMetadata(rs.getLong("id"), rs.getString("object_key"),
                        rs.getString("content_type"), rs.getLong("content_length"), rs.getString("sha256")),
                id);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return rows.getFirst();
    }

    public byte[] read(FileMetadata metadata) {
        try {
            return Files.readAllBytes(root.resolve(metadata.objectKey()));
        } catch (Exception e) {
            throw new IllegalStateException("文件读取失败", e);
        }
    }

    private FileMetadata findBySha(String sha) {
        var rows = jdbcTemplate.query(
                "SELECT id, object_key, content_type, content_length, sha256 FROM file_metadata WHERE sha256 = ?",
                (rs, i) -> new FileMetadata(rs.getLong("id"), rs.getString("object_key"),
                        rs.getString("content_type"), rs.getLong("content_length"), rs.getString("sha256")),
                sha);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private FileResponse toResponse(FileMetadata metadata) {
        return new FileResponse(metadata.id(), metadata.objectKey(), metadata.contentType(),
                metadata.contentLength(), metadata.sha256());
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload.bin";
        }
        return filename.length() > 255 ? filename.substring(0, 255) : filename;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
