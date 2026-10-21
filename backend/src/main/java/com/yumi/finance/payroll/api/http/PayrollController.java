package com.yumi.finance.payroll.api.http;

import com.yumi.finance.payroll.api.AdjustPayrollCommand;
import com.yumi.finance.payroll.api.CalculatePayrollCommand;
import com.yumi.finance.payroll.api.ConfirmEmployeePayrollCommand;
import com.yumi.finance.payroll.api.CreatePayrollPeriodCommand;
import com.yumi.finance.payroll.api.EmployeePayrollView;
import com.yumi.finance.payroll.api.PayrollAdjustmentRef;
import com.yumi.finance.payroll.api.PayrollAdjustmentService;
import com.yumi.finance.payroll.api.PayrollApplicationService;
import com.yumi.finance.payroll.api.PayrollCalculationRef;
import com.yumi.finance.payroll.api.PayrollConfirmationRef;
import com.yumi.finance.payroll.api.PayrollConfirmationService;
import com.yumi.finance.payroll.api.PayrollPeriodRef;
import com.yumi.finance.payroll.api.PayrollQuery;
import com.yumi.finance.payroll.api.PayrollReviewRef;
import com.yumi.finance.payroll.api.PayrollReviewService;
import com.yumi.finance.payroll.api.ReviewPayrollCommand;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.SignedSettlementAmount;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 工资 HTTP 适配器（交付清单 F01）：只调用工资公开命令与 Query，提供期间、参考、逐人核定、
 * 只读复核和人工调整；付款与现金事实归 F04，故不提供付款入口。
 */
@RestController
@RequestMapping("/api/payroll")
public class PayrollController {

    private final PayrollApplicationService payrolls;

    private final PayrollConfirmationService confirmations;

    private final PayrollReviewService reviews;

    private final PayrollAdjustmentService adjustments;

    private final PayrollQuery queries;

    private final com.yumi.finance.payroll.api.PayrollSourceIngestionService ingestion;

    public PayrollController(
            PayrollApplicationService payrolls,
            PayrollConfirmationService confirmations,
            PayrollReviewService reviews,
            PayrollAdjustmentService adjustments,
            PayrollQuery queries,
            com.yumi.finance.payroll.api.PayrollSourceIngestionService ingestion) {
        this.payrolls = payrolls;
        this.confirmations = confirmations;
        this.reviews = reviews;
        this.adjustments = adjustments;
        this.queries = queries;
        this.ingestion = ingestion;
    }

    /** 建立工资期间请求体。 */
    public record CreatePeriodRequest(String periodNo, LocalDate startDate, LocalDate endDate) {
    }

    /** 工资参考运行请求体。 */
    public record CalculateRequest(String periodBusinessId) {
    }

    /** 逐人核定请求体；最终工资由负责人明确填写。 */
    public record ConfirmRequest(
            String resultBusinessId,
            long expectedRowVersion,
            String referenceFingerprint,
            SettlementMoney confirmedAmount,
            String note,
            List<ProcessingItem> processingItems) {

        public record ProcessingItem(
                String referenceLineBusinessId, SettlementMoney manualAmount, String note) {
        }
    }

    /** 已核定工资只读复核请求体。 */
    public record ReviewRequest(String resultBusinessId) {
    }

    /** 人工差额调整请求体。 */
    public record AdjustRequest(
            String resultBusinessId,
            SignedSettlementAmount amountDelta,
            String reasonText,
            String referenceRunBusinessId) {
    }

    @PostMapping("/periods")
    public ResponseEntity<PayrollPeriodRef> createPeriod(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody CreatePeriodRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payrolls.createPeriod(
                new CreatePayrollPeriodCommand(
                        idempotencyKey, request.periodNo(),
                        request.startDate(), request.endDate())));
    }

    @GetMapping("/periods")
    public List<PayrollPeriodRef> listPeriods() {
        return queries.listPeriods();
    }

    @PostMapping("/ingestions")
    public com.yumi.finance.payroll.api.PayrollIngestionRef ingest(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey) {
        return ingestion.ingest(new com.yumi.finance.payroll.api.IngestPayrollSourcesCommand(idempotencyKey));
    }

    @PostMapping("/calculations")
    public ResponseEntity<PayrollCalculationRef> calculate(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody CalculateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payrolls.calculate(
                new CalculatePayrollCommand(idempotencyKey, request.periodBusinessId())));
    }

    @PostMapping("/confirmations")
    public ResponseEntity<PayrollConfirmationRef> confirm(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ConfirmRequest request) {
        List<ConfirmEmployeePayrollCommand.ProcessingItemRequest> items =
                request.processingItems() == null
                        ? List.of()
                        : request.processingItems().stream()
                                .map(item -> new ConfirmEmployeePayrollCommand.ProcessingItemRequest(
                                        item.referenceLineBusinessId(),
                                        item.manualAmount(),
                                        item.note()))
                                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(confirmations.confirmEmployee(
                new ConfirmEmployeePayrollCommand(
                        idempotencyKey, request.resultBusinessId(),
                        request.expectedRowVersion(), request.referenceFingerprint(),
                        request.confirmedAmount(), request.note(), items)));
    }

    @PostMapping("/reviews")
    public ResponseEntity<PayrollReviewRef> review(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviews.review(
                new ReviewPayrollCommand(idempotencyKey, request.resultBusinessId())));
    }

    @PostMapping("/adjustments")
    public ResponseEntity<PayrollAdjustmentRef> adjust(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody AdjustRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adjustments.adjust(
                new AdjustPayrollCommand(
                        idempotencyKey, request.resultBusinessId(), request.amountDelta(),
                        request.reasonText(), request.referenceRunBusinessId())));
    }

    @GetMapping("/periods/{periodBusinessId}/results")
    public List<EmployeePayrollView> listResults(@PathVariable String periodBusinessId) {
        return queries.listPeriodResults(periodBusinessId);
    }

    @GetMapping("/periods/{periodBusinessId}/results/{employeeBusinessId}")
    public EmployeePayrollView getResult(
            @PathVariable String periodBusinessId,
            @PathVariable String employeeBusinessId) {
        return queries.getEmployeePayroll(periodBusinessId, employeeBusinessId);
    }
}
