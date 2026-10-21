package com.yumi.platform.file.application.port;

import java.time.Instant;

/** 文件业务引用记录。 */
public record FileReferenceRecord(
        long id,
        String referenceBusinessId,
        String fileBusinessId,
        String ownerModule,
        String ownerType,
        String ownerBusinessId,
        String purposeCode,
        Instant createdAt,
        String createdBy) {
}
