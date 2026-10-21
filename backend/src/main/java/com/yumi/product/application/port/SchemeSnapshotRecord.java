package com.yumi.product.application.port;

import com.yumi.platform.api.value.ContentFingerprint;
import java.time.Instant;

/** 方案快照记录（模块内部类型）。 */
public record SchemeSnapshotRecord(
        String snapshotBusinessId,
        String schemeBusinessId,
        long snapshotVersion,
        long sourceSchemeContentVersion,
        long sourceProductContentVersion,
        String productSnapshotBusinessId,
        String completeSchemePayloadJson,
        String budgetPayloadJson,
        ContentFingerprint contentFingerprint,
        Instant createdAt) {
}
