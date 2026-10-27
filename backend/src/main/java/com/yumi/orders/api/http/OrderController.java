package com.yumi.orders.api.http;

import com.yumi.customer.api.DeliveryContactOverride;
import com.yumi.orders.api.ChangeConfirmedOrderCommand;
import com.yumi.orders.api.CreateConfirmedOrderCommand;
import com.yumi.orders.api.IntakeLegacyOrderCommand;
import com.yumi.orders.api.LegacyOrderItemRequest;
import com.yumi.orders.api.OpeningOrderService;
import com.yumi.orders.api.OrderApplicationService;
import com.yumi.orders.api.OrderChangeAction;
import com.yumi.orders.api.OrderChangeRef;
import com.yumi.orders.api.OrderChangeService;
import com.yumi.orders.api.OrderItemRequest;
import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.api.OrderRef;
import com.yumi.orders.api.OrderStatus;
import com.yumi.orders.api.OrderSummaryView;
import com.yumi.orders.api.OrderView;
import com.yumi.orders.api.OrderWorkspaceView;
import com.yumi.platform.api.http.HttpHeaders;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单 HTTP 适配器（交付清单 O01）。
 *
 * <p>只调用订单公开命令与 Query，提供确认建单、正式变更、已有订单接管及列表/详情；
 * 不保存草稿，也不在适配层拼接业务规则。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orders;

    private final OrderChangeService changes;

    private final OpeningOrderService openingOrders;

    private final OrderQuery query;

    private final com.yumi.orders.api.OrderCompletionService completions;

    public OrderController(
            OrderApplicationService orders,
            OrderChangeService changes,
            OpeningOrderService openingOrders,
            OrderQuery query,
            com.yumi.orders.api.OrderCompletionService completions) {
        this.orders = orders;
        this.changes = changes;
        this.openingOrders = openingOrders;
        this.query = query;
        this.completions = completions;
    }

    /** 确认建单请求体；一次提交即生成正式订单。 */
    public record CreateOrderRequest(
            String customerBusinessId,
            DeliveryContactOverride contactOverride,
            LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            String note,
            List<OrderItemRequest> items) {

    }

    /** 正式变更请求体；变更分类与内容由公开命令校验。 */
    public record ChangeOrderRequest(
            long expectedContentVersion,
            List<OrderChangeAction> actions,
            String reasonText) {
    }

    /** 已有订单接管请求体；生产状态明细随订单项一并提交。 */
    public record IntakeOrderRequest(
            String customerBusinessId,
            String originalOrderReference,
            LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            String note,
            List<LegacyOrderItemRequest> items) {
    }

    @PostMapping
    public ResponseEntity<OrderRef> createAndConfirm(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody CreateOrderRequest request) {
        OrderRef order = orders.createAndConfirm(new CreateConfirmedOrderCommand(
                idempotencyKey,
                request.customerBusinessId(),
                request.contactOverride(),
                request.orderDate(),
                request.expectedDeliveryDate(),
                request.note(),
                request.items()));
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @PutMapping("/{orderBusinessId}")
    public OrderChangeRef change(
            @PathVariable String orderBusinessId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ChangeOrderRequest request) {
        return changes.change(new ChangeConfirmedOrderCommand(
                idempotencyKey,
                orderBusinessId,
                request.expectedContentVersion(),
                request.actions(),
                request.reasonText()));
    }

    @PostMapping("/opening-orders")
    public ResponseEntity<OrderRef> intakeLegacyOrder(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody IntakeOrderRequest request) {
        OrderRef order = openingOrders.intake(new IntakeLegacyOrderCommand(
                idempotencyKey,
                request.customerBusinessId(),
                request.originalOrderReference(),
                request.orderDate(),
                request.expectedDeliveryDate(),
                request.note(),
                request.items()));
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @GetMapping
    public List<OrderSummaryView> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return query.search(keyword, status, page, size);
    }

    @PostMapping("/{orderBusinessId}/completion")
    public com.yumi.orders.api.OrderCompletionRef complete(
            @PathVariable String orderBusinessId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey) {
        return completions.complete(
                new com.yumi.orders.api.CompleteOrderCommand(idempotencyKey, orderBusinessId));
    }

    @GetMapping("/{orderBusinessId}")
    public OrderView get(@PathVariable String orderBusinessId) {
        return query.get(orderBusinessId);
    }

    @GetMapping("/{orderBusinessId}/workspace")
    public OrderWorkspaceView workspace(@PathVariable String orderBusinessId) {
        return query.workspace(orderBusinessId);
    }
}
