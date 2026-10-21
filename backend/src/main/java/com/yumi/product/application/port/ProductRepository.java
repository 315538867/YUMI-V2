package com.yumi.product.application.port;

import com.yumi.product.api.ProductStatus;
import java.util.List;
import java.util.Optional;

/** 商品持久化端口。 */
public interface ProductRepository {

    Optional<ProductRecord> findByBusinessId(String productBusinessId);

    List<ProductRecord> search(String keyword, ProductStatus status, int offset, int limit);

    void insert(ProductRecord record);

    boolean update(
            ProductRecord record, long expectedRowVersion, java.time.Instant now, String operatorId);

    boolean updateStatus(
            String productBusinessId, ProductStatus status, long expectedRowVersion,
            java.time.Instant now, String operatorId);
}
