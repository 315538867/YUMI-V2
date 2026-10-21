package com.yumi.platform.api.file;

/** 文件引用视图：谁在什么用途上引用了该文件。 */
public record FileReferenceView(
        String referenceBusinessId,
        String ownerModule,
        String ownerType,
        String ownerBusinessId,
        String purposeCode) {
}
