package com.yumi.orders.change;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单变更 API（任务 3.7/3.8）：草稿创建/查询/编辑与确认。
 * 变更确认返回应用后的订单，便于前端直接刷新只读详情。
 */
@RestController
@RequestMapping("/api")
public class OrderChangeController {

    private final OrderChangeService service;

    public OrderChangeController(OrderChangeService service) {
        this.service = service;
    }

    @PostMapping("/orders/{orderId}/change-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderChangeViews.ChangeView create(@PathVariable long orderId,
                                              @RequestBody CreateChangeOrderRequest request) {
        return service.create(orderId, request);
    }

    @GetMapping("/orders/{orderId}/change-orders")
    public List<OrderChangeViews.ChangeView> listByOrder(@PathVariable long orderId) {
        return service.listByOrder(orderId);
    }

    @GetMapping("/order-changes/{id}")
    public OrderChangeViews.ChangeView get(@PathVariable long id) {
        return service.get(id);
    }

    @PatchMapping("/order-changes/{id}")
    public OrderChangeViews.ChangeView update(@PathVariable long id,
                                              @RequestBody CreateChangeOrderRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/order-changes/{id}/confirm")
    public OrderChangeViews.ConfirmResult confirm(@PathVariable long id) {
        return service.confirm(id);
    }
}
