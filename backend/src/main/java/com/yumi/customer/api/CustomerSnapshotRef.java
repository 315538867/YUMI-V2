package com.yumi.customer.api;

import com.yumi.platform.api.value.ContentFingerprint;

/**
 * 客户快照引用。
 *
 * <p>包含所有者、快照类型、稳定标识、快照版本、来源内容版本与内容指纹；消费方只保存本引用。
 */
public record CustomerSnapshotRef(
        String snapshotBusinessId,
        String customerBusinessId,
        long snapshotVersion,
        long sourceContentVersion,
        ContentFingerprint overrideFingerprint,
        ContentFingerprint contentFingerprint) {
}
