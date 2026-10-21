package com.yumi.product.application.port;

import com.yumi.platform.api.value.ContentFingerprint;
import java.time.Instant;

/** 商品快照记录（模块内部类型）。 */
public record ProductSnapshotRecord(
        String snapshotBusinessId,
        String productBusinessId,
        long snapshotVersion,
        long sourceContentVersion,
        String basePayloadJson,
        String processPayloadJson,
        String extraCostPayloadJson,
        int dailyCapacity,
        ContentFingerprint contentFingerprint,
        Instant createdAt) {
}
