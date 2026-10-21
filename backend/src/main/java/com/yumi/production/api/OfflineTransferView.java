package com.yumi.production.api;

import java.time.Instant;

/** 转线下记录：报废转线下或未报废业务移交，两列分开保留来源。 */
public record OfflineTransferView(
        String factKind, String factBusinessId, String productionSourceBusinessId,
        int quantity, Instant occurredAt, String detail, String destination) {
}
