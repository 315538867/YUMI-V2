package com.yumi.orders.order;

import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;

/**
 * 订单 API（任务 3.2）：直接返回业务 DTO，由统一信封包装；错误路径抛 ApiException 走统一错误契约。
 * 确认、变更、取消与履约视图在后续任务（3.4/3.6/3.7/3.9）追加到本控制器。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;
    private final com.yumi.orders.fulfillment.FulfillmentService fulfillmentService;

    public OrderController(OrderService service,
                           com.yumi.orders.fulfillment.FulfillmentService fulfillmentService) {
        this.service = service;
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderViews.OrderDetail create(@RequestBody CreateOrderRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<OrderViews.OrderSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderDateTo) {
        return service.list(status, customerId, orderDateFrom, orderDateTo);
    }

    @GetMapping("/{id}")
    public OrderViews.OrderDetail get(@PathVariable long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public OrderViews.OrderDetail update(@PathVariable long id, @RequestBody UpdateOrderRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/confirm")
    public OrderViews.OrderDetail confirm(@PathVariable long id) {
        return service.confirm(id);
    }

    /** 履约视图（任务 3.6）：共同数量按工序分流 + 四层数量 + 派生状态。 */
    @GetMapping("/{id}/fulfillment")
    public com.yumi.orders.fulfillment.FulfillmentViews.FulfillmentView fulfillment(@PathVariable long id) {
        return fulfillmentService.view(id);
    }

    /** 取消（任务 3.9）。 */
    @PostMapping("/{id}/cancel")
    public OrderViews.OrderDetail cancel(@PathVariable long id, @RequestBody(required = false) CancelRequest request) {
        return service.cancel(id, request == null ? null : request.reason());
    }

    /** 取消入参：原因必填，记入订单取消信息。 */
    public record CancelRequest(String reason) {
    }
}
