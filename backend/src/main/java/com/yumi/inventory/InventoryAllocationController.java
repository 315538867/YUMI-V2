package com.yumi.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存领用 API（任务 4.6/4.9）：与 `/api/inventory` 同属库存模块，但路径按 design.md §6 契约独立。
 */
@RestController
@RequestMapping("/api/inventory-allocations")
public class InventoryAllocationController {

    private final InventoryService service;

    public InventoryAllocationController(InventoryService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryViews.AllocationView allocate(@RequestBody AllocationRequest request) {
        return service.allocate(request);
    }

    @GetMapping
    public List<InventoryViews.AllocationView> allocations(@RequestParam long orderId) {
        return service.listAllocations(orderId);
    }

    @PostMapping("/{id}/cancel")
    public InventoryViews.AllocationView cancel(@PathVariable long id, @RequestBody ReverseRequest request) {
        return service.cancelAllocation(id, request.reason());
    }
}
