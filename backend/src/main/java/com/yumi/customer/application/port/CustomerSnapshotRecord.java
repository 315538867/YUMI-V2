package com.yumi.customer.application.port;

import com.yumi.platform.api.value.ContentFingerprint;
import java.time.Instant;

/** 客户快照记录（模块内部类型）。 */
public record CustomerSnapshotRecord(
        long id,
        String snapshotBusinessId,
        String customerBusinessId,
        long snapshotVersion,
        long sourceContentVersion,
        ContentFingerprint overrideFingerprint,
        String customerNo,
        String name,
        String contactName,
        String phone,
        String address,
        ContentFingerprint contentFingerprint,
        Instant createdAt) {
}
