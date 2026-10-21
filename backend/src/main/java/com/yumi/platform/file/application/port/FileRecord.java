package com.yumi.platform.file.application.port;

import com.yumi.platform.api.file.FileStatus;
import java.time.Instant;

/** 文件元数据记录（平台内部类型）。 */
public record FileRecord(
        long id,
        String fileBusinessId,
        String storageKey,
        String originalName,
        String mediaType,
        long sizeBytes,
        String sha256,
        FileStatus status,
        long rowVersion,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy) {
}
