package com.yumi.product.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.idempotency.CommandRequest;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.http.RequestContextResolver;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import com.yumi.product.api.ChangeProductStatusCommand;
import com.yumi.product.api.CreateCustomSchemeCommand;
import com.yumi.product.api.CreateProductCommand;
import com.yumi.product.api.MaterialSource;
import com.yumi.product.api.ProductApplicationService;
import com.yumi.product.api.ProductQuery;
import com.yumi.product.api.ProductRef;
import com.yumi.product.api.ProductStatus;
import com.yumi.product.api.ProductView;
import com.yumi.product.api.ProductSchemeApplicationService;
import com.yumi.product.api.SchemeRef;
import com.yumi.product.api.SchemeView;
import com.yumi.product.api.UpdateCustomSchemeCommand;
import com.yumi.product.api.UpdateProductCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品 HTTP 适配器（交付清单 D01）。
 *
 * <p>商品与自定义方案只调用商品公开命令与查询；所有写入按平台幂等契约执行，查询不需要幂等键。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductApplicationService products;

    private final ProductQuery productQuery;

    private final ProductSchemeApplicationService schemes;

    private final com.yumi.product.api.ProductOrderEntryQuery orderEntries;

    private final CommandExecutionFacade commands;

    private final ObjectMapper objectMapper;

    private final RequestContextResolver contextResolver;

    public ProductController(
            ProductApplicationService products,
            ProductQuery productQuery,
            ProductSchemeApplicationService schemes,
            com.yumi.product.api.ProductOrderEntryQuery orderEntries,
            CommandExecutionFacade commands,
            ObjectMapper objectMapper,
            RequestContextResolver contextResolver) {
        this.products = products;
        this.productQuery = productQuery;
        this.schemes = schemes;
        this.orderEntries = orderEntries;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.contextResolver = contextResolver;
    }

    /** 修改商品请求体；定义字段与创建命令一致，商品标识来自路径。 */
    public record UpdateProductRequest(CreateProductCommand definition, long expectedRowVersion) {
    }

    /** 商品启停请求体；商品标识来自路径。 */
    public record ChangeStatusRequest(ProductStatus targetStatus, long expectedRowVersion) {
    }

    /** 新建自定义方案请求体；商品标识来自路径。 */
    public record CreateSchemeRequest(
            String name,
            SchemeView.ProcessCode targetProcess,
            SettlementMoney salePrice,
            MaterialSource glueSource,
            MaterialSource colorPasteSource,
            CostAmount makingOtherMaterialCost,
            CostAmount baggingBagUnitCost,
            MaterialSource baggingBagSource,
            CostAmount baggingDecorationUnitPrice,
            MaterialSource baggingDecorationSource,
            CostAmount baggingOtherCost,
            CostAmount seamCombinedMaterialCost,
            CostAmount seamOtherCost,
            long expectedProductContentVersion) {
    }

    /** 修改自定义方案请求体；方案标识来自路径。 */
    public record UpdateSchemeRequest(
            String name,
            SchemeView.ProcessCode targetProcess,
            SettlementMoney salePrice,
            MaterialSource glueSource,
            MaterialSource colorPasteSource,
            CostAmount makingOtherMaterialCost,
            CostAmount baggingBagUnitCost,
            MaterialSource baggingBagSource,
            CostAmount baggingDecorationUnitPrice,
            MaterialSource baggingDecorationSource,
            CostAmount baggingOtherCost,
            CostAmount seamCombinedMaterialCost,
            CostAmount seamOtherCost,
            long expectedProductContentVersion,
            long expectedSchemeRowVersion) {
    }

    /** 停用方案请求体；行版本也可通过查询参数提供。 */
    public record DeactivateSchemeRequest(Long expectedRowVersion) {
    }

    @PostMapping
    public ResponseEntity<ProductView> create(
            @RequestBody CreateProductCommand request, HttpServletRequest servletRequest) {
        var context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new CommandRequest(
                        "product.create",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, request)),
                () -> {
                    ProductRef created = products.create(request);
                    return new CommandOutcome("PRODUCT", created.productBusinessId());
                });
        ProductView view = productQuery.getProduct(execution.outcome().resultBusinessId());
        return ResponseEntity.status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(view);
    }

    @PutMapping("/{productBusinessId}")
    public ProductView update(
            @PathVariable String productBusinessId,
            @RequestBody UpdateProductRequest request,
            HttpServletRequest servletRequest) {
        UpdateProductCommand command = new UpdateProductCommand(
                productBusinessId, request.definition(), request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "product.update",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    ProductRef updated = products.update(command);
                    return new CommandOutcome("PRODUCT", updated.productBusinessId());
                });
        return productQuery.getProduct(productBusinessId);
    }

    @PostMapping("/{productBusinessId}/status")
    public ProductView changeStatus(
            @PathVariable String productBusinessId,
            @RequestBody ChangeStatusRequest request,
            HttpServletRequest servletRequest) {
        ChangeProductStatusCommand command = new ChangeProductStatusCommand(
                productBusinessId, request.targetStatus(), request.expectedRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "product.changeStatus",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    ProductRef updated = products.changeStatus(command);
                    return new CommandOutcome("PRODUCT", updated.productBusinessId());
                });
        return productQuery.getProduct(productBusinessId);
    }

    @GetMapping
    public List<ProductView> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return productQuery.search(keyword, status, page, size);
    }

    @GetMapping("/{productBusinessId}")
    public ProductView get(@PathVariable String productBusinessId) {
        return productQuery.getProduct(productBusinessId);
    }

    @GetMapping("/{productBusinessId}/schemes")
    public List<SchemeView> listSchemes(@PathVariable String productBusinessId) {
        return schemes.listSchemes(productBusinessId);
    }

    @GetMapping("/schemes/{schemeBusinessId}/order-entry")
    public com.yumi.product.api.ProductSnapshotService.SchemeSnapshotRefAndView orderEntry(
            @PathVariable String schemeBusinessId) {
        return orderEntries.getSchemeEntry(schemeBusinessId);
    }

    @PostMapping("/{productBusinessId}/schemes")
    public ResponseEntity<SchemeView> createScheme(
            @PathVariable String productBusinessId,
            @RequestBody CreateSchemeRequest request,
            HttpServletRequest servletRequest) {
        CreateCustomSchemeCommand command = new CreateCustomSchemeCommand(
                productBusinessId,
                request.name(),
                request.targetProcess(),
                request.salePrice(),
                request.glueSource(),
                request.colorPasteSource(),
                request.makingOtherMaterialCost(),
                request.baggingBagUnitCost(),
                request.baggingBagSource(),
                request.baggingDecorationUnitPrice(),
                request.baggingDecorationSource(),
                request.baggingOtherCost(),
                request.seamCombinedMaterialCost(),
                request.seamOtherCost(),
                request.expectedProductContentVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        var execution = commands.execute(
                new CommandRequest(
                        "product.scheme.create",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    SchemeRef created = schemes.createCustomScheme(command);
                    return new CommandOutcome(
                            "PRODUCT_SCHEME", created.schemeBusinessId());
                });
        SchemeView view = findScheme(
                productBusinessId, execution.outcome().resultBusinessId());
        return ResponseEntity.status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(view);
    }

    @PutMapping("/schemes/{schemeBusinessId}")
    public ResponseEntity<SchemeRef> updateScheme(
            @PathVariable String schemeBusinessId,
            @RequestBody UpdateSchemeRequest request,
            HttpServletRequest servletRequest) {
        UpdateCustomSchemeCommand command = new UpdateCustomSchemeCommand(
                schemeBusinessId,
                request.name(),
                request.targetProcess(),
                request.salePrice(),
                request.glueSource(),
                request.colorPasteSource(),
                request.makingOtherMaterialCost(),
                request.baggingBagUnitCost(),
                request.baggingBagSource(),
                request.baggingDecorationUnitPrice(),
                request.baggingDecorationSource(),
                request.baggingOtherCost(),
                request.seamCombinedMaterialCost(),
                request.seamOtherCost(),
                request.expectedProductContentVersion(),
                request.expectedSchemeRowVersion());
        var context = contextResolver.resolveCommand(servletRequest);
        AtomicReference<SchemeRef> updated = new AtomicReference<>();
        commands.execute(
                new CommandRequest(
                        "product.scheme.update",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(objectMapper, command)),
                () -> {
                    SchemeRef ref = schemes.updateCustomScheme(command);
                    updated.set(ref);
                    return new CommandOutcome("PRODUCT_SCHEME", ref.schemeBusinessId());
                });
        return ResponseEntity.ok(updated.get());
    }

    @PostMapping("/schemes/{schemeBusinessId}/deactivate")
    public ResponseEntity<Map<String, String>> deactivateScheme(
            @PathVariable String schemeBusinessId,
            @RequestParam(name = "expectedRowVersion", required = false) Long expectedRowVersion,
            @RequestBody(required = false) DeactivateSchemeRequest request,
            HttpServletRequest servletRequest) {
        long rowVersion = resolveRowVersion(expectedRowVersion, request);
        var context = contextResolver.resolveCommand(servletRequest);
        commands.execute(
                new CommandRequest(
                        "product.scheme.deactivate",
                        context.idempotencyKey(),
                        CommandExecutionFacade.fingerprint(
                                objectMapper,
                                Map.of(
                                        "schemeBusinessId", schemeBusinessId,
                                        "expectedRowVersion", rowVersion))),
                () -> {
                    schemes.deactivateCustomScheme(schemeBusinessId, rowVersion);
                    return new CommandOutcome("PRODUCT_SCHEME", schemeBusinessId);
                });
        return ResponseEntity.ok(Map.of("schemeBusinessId", schemeBusinessId));
    }

    private SchemeView findScheme(String productBusinessId, String schemeBusinessId) {
        return schemes.listSchemes(productBusinessId).stream()
                .filter(scheme -> scheme.schemeBusinessId().equals(schemeBusinessId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "方案写入后无法读取：" + schemeBusinessId));
    }

    private static long resolveRowVersion(
            Long queryRowVersion, DeactivateSchemeRequest request) {
        if (queryRowVersion != null) {
            return queryRowVersion;
        }
        if (request != null && request.expectedRowVersion() != null) {
            return request.expectedRowVersion();
        }
        throw new IllegalArgumentException("expectedRowVersion 不能为空");
    }
}
