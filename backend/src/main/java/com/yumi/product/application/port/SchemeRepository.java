package com.yumi.product.application.port;

import java.util.List;
import java.util.Optional;

/** 方案持久化端口。 */
public interface SchemeRepository {

    Optional<SchemeRecord> findByBusinessId(String schemeBusinessId);

    Optional<SchemeRecord> findStandardOfProduct(String productBusinessId);

    List<SchemeRecord> findAllOfProduct(String productBusinessId);

    void insert(SchemeRecord record);

    boolean update(SchemeRecord record, long expectedRowVersion, java.time.Instant now);

    boolean updateStatus(String schemeBusinessId, String status, long expectedRowVersion, java.time.Instant now);
}
