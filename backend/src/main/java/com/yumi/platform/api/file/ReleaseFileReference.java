package com.yumi.platform.api.file;

/** 释放业务引用（例如凭证被冲销后解除关联）。 */
public record ReleaseFileReference(
        String fileBusinessId,
        String ownerModule,
        String ownerType,
        String ownerBusinessId,
        String purposeCode) {
}
