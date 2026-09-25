package com.yumi.orders.shipment;

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
 * 发货 API（任务 6.2–6.7）：批次挂在订单资源下，不建立顶级发货工作区。
 * 草稿写入幂等；确认、作废、更正必须幂等。
 */
@RestController
@RequestMapping("/api/orders/{orderId}/shipments")
public class ShipmentController {

    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<ShipmentViews.ShipmentView> list(@PathVariable long orderId) {
        return service.list(orderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShipmentViews.ShipmentView createDraft(@PathVariable long orderId,
                                                  @RequestBody ShipmentViews.ShipmentDraftRequest request) {
        return service.createDraft(orderId, request);
    }

    @PatchMapping("/{shipmentId}")
    public ShipmentViews.ShipmentView updateDraft(@PathVariable long orderId, @PathVariable long shipmentId,
                                                  @RequestBody ShipmentViews.ShipmentDraftRequest request) {
        return service.updateDraft(shipmentId, request);
    }

    @PostMapping("/{shipmentId}/confirm")
    public ShipmentViews.ShipmentView confirm(@PathVariable long orderId, @PathVariable long shipmentId) {
        return service.confirm(orderId, shipmentId);
    }

    @PatchMapping("/{shipmentId}/logistics")
    public ShipmentViews.ShipmentView changeLogistics(@PathVariable long orderId, @PathVariable long shipmentId,
                                                      @RequestBody ShipmentViews.LogisticsChangeRequest request) {
        return service.changeLogistics(shipmentId, request);
    }

    @PostMapping("/{shipmentId}/void")
    public ShipmentViews.ShipmentView voidShipment(@PathVariable long orderId, @PathVariable long shipmentId,
                                                   @RequestBody ShipmentViews.VoidRequest request) {
        return service.voidShipment(shipmentId, request.reason());
    }

    @PostMapping("/{shipmentId}/corrections")
    public ShipmentViews.ShipmentView correct(@PathVariable long orderId, @PathVariable long shipmentId,
                                              @RequestBody ShipmentViews.CorrectionRequest request) {
        return service.correct(shipmentId, request.reason());
    }
}
