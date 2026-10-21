package com.yumi.platform.api.file;

import java.time.Instant;
import java.util.List;

/** 文件元数据视图；不含存储路径。 */
public record FileView(
        String fileBusinessId,
        String originalName,
        String mediaType,
        long sizeBytes,
        String sha256,
        FileStatus status,
        long rowVersion,
        List<FileReferenceView> references,
        Instant createdAt) {
}
