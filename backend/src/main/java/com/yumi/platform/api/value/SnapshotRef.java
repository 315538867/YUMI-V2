package com.yumi.platform.api.value;

import java.util.Objects;

/**
 * 完整快照引用：所有者模块、快照类型、稳定业务标识、快照版本、来源内容版本与内容指纹。
 *
 * <p>消费方只保存本引用，不复制快照载荷；不得只保存裸版本号。
 */
public record SnapshotRef(
        String ownerModule,
        String snapshotType,
        StableBusinessId snapshotBusinessId,
        long snapshotVersion,
        long sourceContentVersion,
        ContentFingerprint contentFingerprint) {

    public SnapshotRef {
        Objects.requireNonNull(ownerModule, "快照所有者模块不能为空");
        Objects.requireNonNull(snapshotType, "快照类型不能为空");
        Objects.requireNonNull(snapshotBusinessId, "快照稳定业务标识不能为空");
        Objects.requireNonNull(contentFingerprint, "快照内容指纹不能为空");
        if (ownerModule.isBlank()) {
            throw new IllegalArgumentException("快照所有者模块不能为空白");
        }
        if (snapshotType.isBlank()) {
            throw new IllegalArgumentException("快照类型不能为空白");
        }
        if (snapshotVersion <= 0) {
            throw new IllegalArgumentException("快照版本必须为正数：" + snapshotVersion);
        }
        if (sourceContentVersion <= 0) {
            throw new IllegalArgumentException("来源内容版本必须为正数：" + sourceContentVersion);
        }
    }
}
