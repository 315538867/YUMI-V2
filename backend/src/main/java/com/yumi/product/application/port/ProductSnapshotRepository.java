package com.yumi.product.application.port;

import java.util.Optional;

/** 商品与方案快照持久化端口。 */
public interface ProductSnapshotRepository {

    Optional<ProductSnapshotRecord> findProductSnapshot(String productBusinessId, long sourceContentVersion);

    Optional<ProductSnapshotRecord> findProductSnapshotByBusinessId(String snapshotBusinessId);

    long maxProductSnapshotVersion(String productBusinessId);

    void insertProductSnapshot(ProductSnapshotRecord record);

    Optional<SchemeSnapshotRecord> findSchemeSnapshot(
            String schemeBusinessId, long sourceSchemeContentVersion, long sourceProductContentVersion);

    Optional<SchemeSnapshotRecord> findSchemeSnapshotByBusinessId(String snapshotBusinessId);

    long maxSchemeSnapshotVersion(String schemeBusinessId);

    void insertSchemeSnapshot(SchemeSnapshotRecord record);
}
