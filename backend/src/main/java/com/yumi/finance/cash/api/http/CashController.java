package com.yumi.finance.cash.api.http;

import com.yumi.finance.cash.api.CashApplicationService;
import com.yumi.finance.cash.api.CashFactRef;
import com.yumi.finance.cash.api.CashQuery;
import com.yumi.finance.cash.api.PayPayrollCommand;
import com.yumi.finance.cash.api.ReceiveOrderPaymentCommand;
import com.yumi.finance.cash.api.RefundAfterSalesPaymentCommand;
import com.yumi.finance.cash.api.RefundOrderPaymentCommand;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;
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
 * 现金 HTTP 适配器（交付清单 F04）。
 *
 * <p>订单、售后与工资收付都只提交现金命令；资格由对应模块参与接口在同一事务校验。
 */
@RestController
@RequestMapping("/api/finance/cash")
public class CashController {

    private final CashApplicationService cash;
    private final CashQuery queries;

    public CashController(CashApplicationService cash, CashQuery queries) {
        this.cash = cash;
        this.queries = queries;
    }

    @GetMapping("/facts")
    public List<CashQuery.CashFactView> facts(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) String cashType) {
        return queries.search(
                LocalDate.parse(startDate), LocalDate.parse(endDate), cashType);
    }

    @GetMapping("/facts/{cashFactBusinessId}")
    public CashQuery.CashFactDetailView fact(@PathVariable String cashFactBusinessId) {
        return queries.getFact(cashFactBusinessId);
    }

    @GetMapping("/period-summary")
    public CashQuery.CashPeriodSummary periodSummary(
            @RequestParam String startDate, @RequestParam String endDate) {
        return queries.getPeriodSummary(LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    @GetMapping("/source-summary")
    public CashQuery.SourceCashSummary sourceSummary(
            @RequestParam String sourceModule,
            @RequestParam String sourceType,
            @RequestParam String sourceBusinessId) {
        return queries.getSourceSummary(sourceModule, sourceType, sourceBusinessId);
    }

    @PostMapping("/order-receipts")
    public ResponseEntity<CashFactRef> receiveOrder(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody OrderPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cash.receiveOrder(
                new ReceiveOrderPaymentCommand(
                        idempotencyKey, request.orderBusinessId(),
                        request.amount(), request.businessDate(),
                        nullToEmpty(request.voucherFileRefs()))));
    }

    @PostMapping("/order-refunds")
    public ResponseEntity<CashFactRef> refundOrder(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody OrderPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cash.refundOrder(
                new RefundOrderPaymentCommand(
                        idempotencyKey, request.orderBusinessId(),
                        request.amount(), request.businessDate(),
                        nullToEmpty(request.voucherFileRefs()))));
    }

    @PostMapping("/after-sales-payments")
    public ResponseEntity<CashFactRef> refundAfterSales(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody AfterSalesPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cash.refundAfterSales(
                new RefundAfterSalesPaymentCommand(
                        idempotencyKey, request.obligationBusinessId(),
                        request.amount(), request.businessDate(),
                        nullToEmpty(request.voucherFileRefs()))));
    }

    @PostMapping("/payroll-payments")
    public ResponseEntity<CashFactRef> payPayroll(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody PayrollPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cash.payPayroll(
                new PayPayrollCommand(
                        idempotencyKey,
                        request.allocations().stream()
                                .map(allocation -> new PayPayrollCommand.PayrollAllocation(
                                        allocation.resultBusinessId(), allocation.amount()))
                                .toList(),
                        request.businessDate())));
    }

    @PostMapping("/daily-cash")
    public ResponseEntity<com.yumi.finance.cash.api.DailyCashRecordRef> recordDailyCash(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody DailyCashRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cash.recordDailyCash(
                new com.yumi.finance.cash.api.RecordDailyCashCommand(
                        idempotencyKey, request.direction(), request.categoryCode(),
                        request.businessDate(), request.amount(),
                        request.description(), request.countAsExpense(),
                        nullToEmpty(request.voucherFileRefs()))));
    }

    @PostMapping("/payroll-returns")
    public ResponseEntity<CashFactRef> returnPayroll(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody PayrollReturnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cash.returnPayroll(
                new com.yumi.finance.cash.api.ReturnPayrollPaymentCommand(
                        idempotencyKey, request.originalCashFactBusinessId(),
                        request.resultBusinessId(), request.amount(),
                        request.businessDate(), request.reasonText(), List.of())));
    }

    @PostMapping("/reversals")
    public ResponseEntity<CashFactRef> reverse(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ReversalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cash.reverse(
                new com.yumi.finance.cash.api.ReverseCashFactCommand(
                        idempotencyKey, request.cashFactBusinessId(), request.businessDate(),
                        request.reasonText())));
    }

    public record OrderPaymentRequest(
            String orderBusinessId, SettlementMoney amount, LocalDate businessDate,
            List<String> voucherFileRefs) {
    }

    public record AfterSalesPaymentRequest(
            String obligationBusinessId, SettlementMoney amount, LocalDate businessDate,
            List<String> voucherFileRefs) {
    }

    public record PayrollPaymentRequest(
            List<PayrollAllocation> allocations, LocalDate businessDate) {

        public record PayrollAllocation(String resultBusinessId, SettlementMoney amount) {
        }
    }

    public record PayrollReturnRequest(
            String originalCashFactBusinessId, String resultBusinessId,
            SettlementMoney amount, LocalDate businessDate, String reasonText) {
    }

    public record ReversalRequest(
            String cashFactBusinessId, LocalDate businessDate, String reasonText) {
    }

    public record DailyCashRequest(
            String direction, String categoryCode, LocalDate businessDate,
            SettlementMoney amount, String description, boolean countAsExpense,
            List<String> voucherFileRefs) {
    }
    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
