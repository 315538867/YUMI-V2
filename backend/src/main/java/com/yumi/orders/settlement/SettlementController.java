package com.yumi.orders.settlement;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收退款与关闭 API（任务 7.2/7.3/7.5）：挂在订单资源下。
 * 收款与退款写入幂等；关闭必须幂等。事实不可编辑删除。
 */
@RestController
@RequestMapping("/api/orders/{orderId}")
public class SettlementController {

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    @GetMapping("/settlement")
    public SettlementViews.SettlementView view(@PathVariable long orderId) {
        return service.view(orderId);
    }

    @PostMapping("/payments")
    public SettlementViews.SettlementView registerPayment(@PathVariable long orderId,
                                                          @RequestBody SettlementViews.PaymentRequest request) {
        return service.registerPayment(orderId, request);
    }

    @PostMapping("/refunds")
    public SettlementViews.SettlementView registerRefund(@PathVariable long orderId,
                                                         @RequestBody SettlementViews.RefundRequest request) {
        return service.registerRefund(orderId, request);
    }

    @PostMapping("/close")
    public SettlementViews.SettlementView close(@PathVariable long orderId) {
        return service.close(orderId);
    }
}
