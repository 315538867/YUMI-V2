package com.yumi.orders.aftersales;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 售后 API（任务 8.2–8.9）：创建与查询挂在订单资源下，处理命令挂在售后单资源下。
 * 不提供顶级售后工作区；退回核验与补发确认为「必须幂等」。
 */
@RestController
public class AfterSalesController {

    private final AfterSalesService service;

    public AfterSalesController(AfterSalesService service) {
        this.service = service;
    }

    @GetMapping("/api/orders/{orderId}/after-sales")
    public List<AfterSalesViews.AfterSalesCaseView> list(@PathVariable long orderId) {
        return service.list(orderId);
    }

    @PostMapping("/api/orders/{orderId}/after-sales")
    @ResponseStatus(HttpStatus.CREATED)
    public AfterSalesViews.AfterSalesCaseView create(@PathVariable long orderId,
                                                     @RequestBody AfterSalesViews.CreateAfterSalesRequest request) {
        return service.create(orderId, request);
    }

    @GetMapping("/api/after-sales/{caseId}")
    public AfterSalesViews.AfterSalesCaseView get(@PathVariable long caseId) {
        return service.get(caseId);
    }

    @PostMapping("/api/after-sales/{caseId}/verify-return")
    public AfterSalesViews.AfterSalesCaseView verifyReturn(
            @PathVariable long caseId, @RequestBody AfterSalesViews.VerifyReturnRequest request) {
        return service.verifyReturn(caseId, request);
    }

    @PostMapping("/api/after-sales/{caseId}/replacement-shipments")
    @ResponseStatus(HttpStatus.CREATED)
    public AfterSalesViews.AfterSalesCaseView createReplacementShipment(
            @PathVariable long caseId, @RequestBody AfterSalesViews.ReplacementShipmentRequest request) {
        return service.createReplacementShipment(caseId, request);
    }

    @PostMapping("/api/after-sales/{caseId}/replacement-shipments/{shipmentId}/confirm")
    public AfterSalesViews.AfterSalesCaseView confirmReplacementShipment(@PathVariable long caseId,
                                                                        @PathVariable long shipmentId) {
        return service.confirmReplacementShipment(caseId, shipmentId);
    }

    @PostMapping("/api/after-sales/{caseId}/corrections")
    public AfterSalesViews.AfterSalesCaseView correct(@PathVariable long caseId,
                                                      @RequestBody AfterSalesViews.CorrectionRequest request) {
        return service.correct(caseId, request);
    }
}
