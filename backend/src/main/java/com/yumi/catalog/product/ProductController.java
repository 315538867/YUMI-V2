package com.yumi.catalog.product;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品 API（任务 2.2、2.16）：直接返回业务 DTO，由统一信封包装；
 * 错误路径抛 ApiException 走统一错误契约。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDetail create(@RequestBody CreateProductRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<ProductSummary> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String name) {
        return service.list(status, name);
    }

    @GetMapping("/{id}")
    public ProductDetail get(@PathVariable long id) {
        return service.get(id);
    }

    /** 新建试算：只读，不要求 Idempotency-Key，不产生业务写入（见 WritePolicy）。 */
    @PostMapping("/preview")
    public ProductPreview previewCreate(@RequestBody CreateProductRequest request) {
        return service.previewCreate(request);
    }

    /** 编辑试算：只读，沿用 PATCH 缺省合并语义，需携带商品版本号。 */
    @PostMapping("/{id}/preview")
    public ProductPreview previewUpdate(@PathVariable long id, @RequestBody UpdateProductRequest request) {
        return service.previewUpdate(id, request);
    }

    @PatchMapping("/{id}")
    public ProductDetail update(@PathVariable long id, @RequestBody UpdateProductRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/enable")
    public ProductDetail enable(@PathVariable long id,
                                @RequestBody(required = false) ToggleProductRequest request) {
        return service.changeStatus(id, true, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/disable")
    public ProductDetail disable(@PathVariable long id,
                                 @RequestBody(required = false) ToggleProductRequest request) {
        return service.changeStatus(id, false, request == null ? null : request.reason());
    }
}
