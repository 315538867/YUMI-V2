package com.yumi.orders.api.http;

import com.yumi.orders.api.ConfirmShipmentCommand;
import com.yumi.orders.api.CorrectShipmentCommand;
import com.yumi.orders.api.OrderDeliveryService;
import com.yumi.orders.api.OrderQuery;
import com.yumi.orders.api.OrderView;
import com.yumi.orders.api.ShipmentItemRequest;
import com.yumi.orders.api.ShipmentRef;
import com.yumi.platform.api.http.HttpHeaders;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单发货 HTTP 适配器（交付清单 O02）。
 *
 * <p>只调用订单交付公开命令与 Query，提供发货确认、发货更正与交付履约查询；
 * 收退款入口归 F04，本控制器不暴露任何现金命令。
 */
@RestController
@RequestMapping("/api/orders/delivery")
public class OrderDeliveryController {

    private final OrderDeliveryService delivery;

    private final OrderQuery query;

    public OrderDeliveryController(OrderDeliveryService delivery, OrderQuery query) {
        this.delivery = delivery;
        this.query = query;
    }

    /** 发货确认请求体；一条明细消费一个生产来源。 */
    public record ShipmentRequest(
            String orderBusinessId,
            List<ShipmentItemRequest> items,
            String trackingNo,
            LocalDateTime shippedAt) {
    }

    /** 发货更正请求体；已确认发货只能通过更正事实修正。 */
    public record ShipmentCorrectionRequest(
            String shipmentBusinessId,
            String shipmentItemBusinessId,
            int quantityDecrease,
            String reasonText) {
    }

    @PostMapping("/shipments")
    public ResponseEntity<ShipmentRef> confirmShipment(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ShipmentRequest request) {
        ShipmentRef shipment = delivery.confirmShipment(new ConfirmShipmentCommand(
                idempotencyKey,
                request.orderBusinessId(),
                request.items(),
                request.trackingNo(),
                request.shippedAt()));
        return ResponseEntity.status(HttpStatus.CREATED).body(shipment);
    }

    @PostMapping("/shipments/corrections")
    public ShipmentRef correctShipment(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ShipmentCorrectionRequest request) {
        return delivery.correctShipment(new CorrectShipmentCommand(
                idempotencyKey,
                request.shipmentBusinessId(),
                request.shipmentItemBusinessId(),
                request.quantityDecrease(),
                request.reasonText()));
    }

    @GetMapping("/fulfillment/{orderBusinessId}")
    public OrderView.FulfillmentView fulfillment(@PathVariable String orderBusinessId) {
        return query.getFulfillment(orderBusinessId);
    }
}
