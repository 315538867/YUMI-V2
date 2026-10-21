package com.yumi.product.api;

import java.util.List;

/** 商品只读查询。 */
public interface ProductQuery {

    ProductView getProduct(String productBusinessId);

    List<ProductView> search(String keyword, ProductStatus status, int page, int size);
}
