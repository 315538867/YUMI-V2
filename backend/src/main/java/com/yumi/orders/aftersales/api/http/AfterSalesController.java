package com.yumi.orders.aftersales.api.http;

import com.yumi.orders.aftersales.api.AfterSalesApplicationService;
import com.yumi.orders.aftersales.api.AfterSalesCancellationRef;
import com.yumi.orders.aftersales.api.AfterSalesCaseRef;
import com.yumi.orders.aftersales.api.AfterSalesCaseView;
import com.yumi.orders.aftersales.api.AfterSalesCompletionRef;
import com.yumi.orders.aftersales.api.AfterSalesDeliveryRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeRef;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeRoute;
import com.yumi.orders.aftersales.api.AfterSalesObligationChangeService;
import com.yumi.orders.aftersales.api.AfterSalesObligationRequest;
import com.yumi.orders.aftersales.api.AfterSalesQuery;
import com.yumi.orders.aftersales.api.AfterSalesRepairRef;
import com.yumi.orders.aftersales.api.AfterSalesReturnService;
import com.yumi.orders.aftersales.api.AfterSalesRouteService;
import com.yumi.orders.aftersales.api.AfterSalesRouteType;
import com.yumi.orders.aftersales.api.CancelAfterSalesObligationCommand;
import com.yumi.orders.aftersales.api.ChangeAfterSalesObligationCommand;
import com.yumi.orders.aftersales.api.CompleteAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.DisposeReturnedProductCommand;
import com.yumi.orders.aftersales.api.OpenAfterSalesCaseCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnCommand;
import com.yumi.orders.aftersales.api.ReceiveReturnRef;
import com.yumi.orders.aftersales.api.RequestRepairCommand;
import com.yumi.orders.aftersales.api.ReturnDispositionRef;
import com.yumi.orders.aftersales.api.ReturnToCustomerCommand;
import com.yumi.orders.api.RecipientInfo;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.production.api.PhysicalDestination;
import com.yumi.production.api.ResponsibilityType;
import com.yumi.production.api.ReturnDispositionType;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 售后 HTTP 适配器（交付清单 A01）。
 *
 * <p>只调用售后公开命令与 Query，覆盖案例受理/查询、退回接收、原样退还、退回制品池处置、
 * 返修路线、义务变更/取消与结案；退回池处置只在此暴露，生产侧不提供独立处置路由。
 */
@RestController
@RequestMapping("/api/after-sales")
public class AfterSalesController {

    private final AfterSalesApplicationService cases;

    private final AfterSalesQuery queries;

    private final AfterSalesReturnService returns;

    private final AfterSalesRouteService routes;

    private final AfterSalesObligationChangeService obligationChanges;

    public AfterSalesController(
            AfterSalesApplicationService cases,
            AfterSalesQuery queries,
            AfterSalesReturnService returns,
            AfterSalesRouteService routes,
            AfterSalesObligationChangeService obligationChanges) {
        this.cases = cases;
        this.queries = queries;
        this.returns = returns;
        this.routes = routes;
        this.obligationChanges = obligationChanges;
    }

    /** 售后受理请求体；只允许引用一个已完成订单。 */
    public record OpenCaseRequest(
            String orderBusinessId,
            String issueSummary,
            AfterSalesRouteType routeType,
            List<AfterSalesObligationRequest> obligations) {
    }

    /** 退回接收请求体；服务端按最近交付追溯原消费事实。 */
    public record ReceiveReturnRequest(
            String obligationBusinessId, int quantity, String conditionCode) {
    }

    /** 退回制品池处置请求体；原样退还走售后交付命令，不在此提交。 */
    public record DisposeReturnedProductRequest(
            String receiptItemBusinessId,
            int quantity,
            ReturnDispositionType dispositionType,
            PhysicalDestination physicalDestination,
            LocalDate businessDate,
            String reasonText,
            ResponsibilityType responsibilityType,
            String responsibleSubjectBusinessId,
            String waivedReason,
            String offlineInventoryName,
            String offlineInventoryNote,
            String handedOverBy,
            String receivedBy,
            LocalDateTime transferredAt) {
    }

    /** 原样退还请求体；池处置与售后交付同事务完成。 */
    public record ReturnToCustomerRequest(
            String receiptItemBusinessId,
            int quantity,
            LocalDateTime deliveredAt,
            RecipientInfo recipient,
            String trackingNo) {
    }

    /** 返修请求体；把已退回制品转为原物修复。 */
    public record RequestRepairRequest(
            String obligationBusinessId, String receiptItemBusinessId, int quantity) {
    }

    /** 义务转路线请求体；退款/赔付必须给出金额。 */
    public record ChangeObligationRequest(
            int quantity,
            AfterSalesObligationChangeRoute route,
            SettlementMoney amount,
            String note) {
    }

    /** 义务取消请求体；仅池可用数量新增处置，已路由终态不重复退出。 */
    public record CancelObligationRequest(
            ReturnDispositionType poolDispositionType,
            PhysicalDestination poolPhysicalDestination,
            ResponsibilityType responsibilityType,
            String responsibleSubjectBusinessId,
            String waivedReason,
            String offlineInventoryName,
            String offlineInventoryNote,
            String handedOverBy,
            String receivedBy,
            LocalDateTime transferredAt,
            LocalDate businessDate,
            String reasonText) {
    }

    @PostMapping("/cases")
    public ResponseEntity<AfterSalesCaseRef> open(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody OpenCaseRequest request) {
        AfterSalesCaseRef opened = cases.open(new OpenAfterSalesCaseCommand(
                idempotencyKey,
                request.orderBusinessId(),
                request.issueSummary(),
                request.routeType(),
                request.obligations()));
        return ResponseEntity.status(HttpStatus.CREATED).body(opened);
    }

    @GetMapping("/cases")
    public java.util.List<AfterSalesCaseView> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) com.yumi.orders.aftersales.api.AfterSalesCaseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.search(keyword, status, page, size);
    }

    @GetMapping("/cases/{caseBusinessId}")
    public AfterSalesCaseView get(@PathVariable String caseBusinessId) {
        return queries.get(caseBusinessId);
    }

    @PostMapping("/returns")
    public ResponseEntity<ReceiveReturnRef> receiveReturn(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ReceiveReturnRequest request) {
        ReceiveReturnRef receipt = returns.receiveReturn(new ReceiveReturnCommand(
                idempotencyKey,
                request.obligationBusinessId(),
                request.quantity(),
                request.conditionCode()));
        return ResponseEntity.status(HttpStatus.CREATED).body(receipt);
    }

    @PostMapping("/returns/dispositions")
    public ResponseEntity<ReturnDispositionRef> disposeReturnedProduct(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody DisposeReturnedProductRequest request) {
        ReturnDispositionRef disposition = cases.disposeReturnedProduct(
                new DisposeReturnedProductCommand(
                        idempotencyKey,
                        request.receiptItemBusinessId(),
                        request.quantity(),
                        request.dispositionType(),
                        request.physicalDestination(),
                        request.businessDate(),
                        request.reasonText(),
                        request.responsibilityType(),
                        request.responsibleSubjectBusinessId(),
                        request.waivedReason(),
                        request.offlineInventoryName(),
                        request.offlineInventoryNote(),
                        request.handedOverBy(),
                        request.receivedBy(),
                        request.transferredAt()));
        return ResponseEntity.status(HttpStatus.CREATED).body(disposition);
    }

    @PostMapping("/returns/return-to-customer")
    public ResponseEntity<AfterSalesDeliveryRef> returnToCustomer(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ReturnToCustomerRequest request) {
        AfterSalesDeliveryRef delivery = returns.returnToCustomer(new ReturnToCustomerCommand(
                idempotencyKey,
                request.receiptItemBusinessId(),
                request.quantity(),
                request.deliveredAt(),
                request.recipient(),
                request.trackingNo()));
        return ResponseEntity.status(HttpStatus.CREATED).body(delivery);
    }

    @PostMapping("/repairs")
    public ResponseEntity<AfterSalesRepairRef> requestRepair(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody RequestRepairRequest request) {
        AfterSalesRepairRef repair = routes.requestRepair(new RequestRepairCommand(
                idempotencyKey,
                request.obligationBusinessId(),
                request.receiptItemBusinessId(),
                request.quantity()));
        return ResponseEntity.status(HttpStatus.CREATED).body(repair);
    }

    @PostMapping("/obligations/{obligationBusinessId}/changes")
    public ResponseEntity<AfterSalesObligationChangeRef> changeObligation(
            @PathVariable String obligationBusinessId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ChangeObligationRequest request) {
        AfterSalesObligationChangeRef change = routes.changeObligation(
                new ChangeAfterSalesObligationCommand(
                        idempotencyKey,
                        obligationBusinessId,
                        request.quantity(),
                        request.route(),
                        request.amount(),
                        request.note()));
        return ResponseEntity.status(HttpStatus.CREATED).body(change);
    }

    @PostMapping("/obligations/{obligationBusinessId}/cancellations")
    public AfterSalesCancellationRef cancelObligation(
            @PathVariable String obligationBusinessId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody CancelObligationRequest request) {
        return obligationChanges.cancel(new CancelAfterSalesObligationCommand(
                idempotencyKey,
                obligationBusinessId,
                request.poolDispositionType(),
                request.poolPhysicalDestination(),
                request.responsibilityType(),
                request.responsibleSubjectBusinessId(),
                request.waivedReason(),
                request.offlineInventoryName(),
                request.offlineInventoryNote(),
                request.handedOverBy(),
                request.receivedBy(),
                request.transferredAt(),
                request.businessDate(),
                request.reasonText()));
    }

    @PostMapping("/cases/{caseBusinessId}/completion")
    public AfterSalesCompletionRef complete(
            @PathVariable String caseBusinessId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey) {
        return cases.complete(new CompleteAfterSalesCaseCommand(idempotencyKey, caseBusinessId));
    }
}
