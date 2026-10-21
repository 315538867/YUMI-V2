package com.yumi.product.api;

import com.yumi.platform.api.value.ContentFingerprint;

/**
 * 商品或方案快照引用。
 *
 * <p>消费方只保存引用；快照本身不可修改，定义变化只能产生新版本。
 *
 * @param snapshotType {@code PRODUCT} 或 {@code SCHEME}
 * @param snapshotBusinessId 快照稳定标识
 * @param ownerBusinessId 商品或方案标识
 * @param snapshotVersion 快照版本（同一所有者内递增）
 * @param sourceContentVersion 冻结时的内容版本
 * @param contentFingerprint 快照内容指纹
 */
public record ProductSnapshotRef(
        SnapshotType snapshotType,
        String snapshotBusinessId,
        String ownerBusinessId,
        long snapshotVersion,
        long sourceContentVersion,
        ContentFingerprint contentFingerprint) {

    /** 快照类型。 */
    public enum SnapshotType {
        PRODUCT,
        SCHEME
    }
}
