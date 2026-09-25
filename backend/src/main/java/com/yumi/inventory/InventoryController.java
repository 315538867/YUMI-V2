package com.yumi.inventory;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 库存 API（任务 4.2–4.5）：批次/汇总/流水查询、期初入库、盘点调整与领用推荐。
 * 领用与取消在 4.6/4.9 追加；直接返回业务 DTO，由统一信封包装。
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/batches")
    public List<InventoryViews.BatchView> batches(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String node,
            @RequestParam(required = false) String seamState,
            @RequestParam(required = false, defaultValue = "false") boolean includeEmpty) {
        return service.listBatches(productId, node, seamState, includeEmpty);
    }

    @GetMapping("/summary")
    public List<InventoryViews.SummaryView> summary(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false, defaultValue = "false") boolean includeEmpty) {
        return service.summary(productId, includeEmpty);
    }

    @GetMapping("/movements")
    public List<InventoryViews.MovementView> movements(
            @RequestParam(required = false) String movementType,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDateTo) {
        return service.listMovements(movementType, batchId, businessDateFrom, businessDateTo);
    }

    /** 期初入库（任务 4.3）。 */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryViews.BatchView opening(@RequestBody OpeningRequest request) {
        return service.opening(request);
    }

    /** 盘点调整（任务 4.4）。 */
    @PostMapping("/adjustments")
    public InventoryViews.BatchView adjust(@RequestBody AdjustmentRequest request) {
        return service.adjust(request);
    }

    /** 流水冲销（任务 4.10）：新增反向流水并保留关联历史。 */
    /** 售后库存领用（任务 8.6）：从成品批次扣库存，只增加售后可补发。 */
    @PostMapping("/after-sales-allocations")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public InventoryViews.MovementView allocateToAfterSales(
            @RequestBody AfterSalesAllocationRequest request) {
        return service.allocateToAfterSales(request);
    }

    @PostMapping("/movements/{id}/reverse")
    public InventoryViews.MovementView reverseMovement(@PathVariable long id,
                                                       @RequestBody ReverseRequest request) {
        return service.reverseMovement(id, request.reason());
    }

    /** 领用推荐（任务 4.5）：只推荐不占用。 */
    @GetMapping("/recommendations")
    public List<InventoryViews.RecommendationView> recommendations(
            @RequestParam long productId,
            @RequestParam String targetNode) {
        return service.recommendations(productId, targetNode);
    }
}
