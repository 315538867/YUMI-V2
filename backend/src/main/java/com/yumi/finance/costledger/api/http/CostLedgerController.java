package com.yumi.finance.costledger.api.http;

import com.yumi.finance.costledger.api.AccrueAfterSalesCostsCommand;
import com.yumi.finance.costledger.api.AccrueDirectLaborCostsCommand;
import com.yumi.finance.costledger.api.AccrueProductionMaterialCostsCommand;
import com.yumi.finance.costledger.api.AdjustCostCommand;
import com.yumi.finance.costledger.api.AllocateCostCommand;
import com.yumi.finance.costledger.api.CostAccrualRunRef;
import com.yumi.finance.costledger.api.CostEntryRef;
import com.yumi.finance.costledger.api.CostLedgerApplicationService;
import com.yumi.finance.costledger.api.CostLedgerQuery;
import com.yumi.finance.costledger.api.OperatingExpenseCommand;
import com.yumi.finance.costledger.api.OperatingExpenseService;
import com.yumi.finance.costledger.api.RetryCostFailureCommand;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.idempotency.CommandExecutionFacade;
import com.yumi.platform.api.idempotency.CommandOutcome;
import com.yumi.platform.api.value.CostAmount;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 成本台账 HTTP 适配器（交付清单 F03）：只提交归集、失败重试、精确更正、显式分摊与经营费用命令，
 * 并读取成本明细、失败队列和主体汇总，不直接访问任何成本表。
 */
@RestController
@RequestMapping("/api/finance/cost-ledger")
public class CostLedgerController {

    private final CostLedgerApplicationService ledger;

    private final CostLedgerQuery queries;

    private final OperatingExpenseService expenses;

    private final CommandExecutionFacade commands;

    public CostLedgerController(
            CostLedgerApplicationService ledger,
            CostLedgerQuery queries,
            OperatingExpenseService expenses,
            CommandExecutionFacade commands) {
        this.ledger = ledger;
        this.queries = queries;
        this.expenses = expenses;
        this.commands = commands;
    }

    /** 按日期范围归集请求体。 */
    public record AccrualRequest(LocalDate startDate, LocalDate endDate) {
    }

    /** 失败重试请求体。 */
    public record RetryRequest(LocalDate startDate, LocalDate endDate) {
    }

    /** 精确更正请求体：新金额替代历史成本。 */
    public record AdjustRequest(CostAmount newAmount, String reasonText) {
    }

    /** 显式分摊请求体。 */
    public record AllocationRequest(List<AllocationItem> allocations) {

        public record AllocationItem(
                String subjectType, String subjectBusinessId, CostAmount amount) {
        }
    }

    /** 经营费用入账请求体。 */
    public record OperatingExpenseRequest(
            String expenseBusinessId,
            String cashFactBusinessId,
            CostAmount amount,
            LocalDate businessDate,
            String categoryCode) {
    }

    /** 经营费用反向请求体。 */
    public record OperatingExpenseReversalRequest(String reasonText) {
    }

    /** 经营费用反向结果。 */
    public record OperatingExpenseReversalResponse(
            String expenseBusinessId, String reversalExpenseBusinessId) {
    }

    @PostMapping("/material-accruals")
    public ResponseEntity<CostAccrualRunRef> accrueMaterialCosts(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody AccrualRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ledger.accrueProductionMaterialCosts(new AccrueProductionMaterialCostsCommand(
                        idempotencyKey, request.startDate(), request.endDate())));
    }

    @PostMapping("/labor-accruals")
    public ResponseEntity<CostAccrualRunRef> accrueLaborCosts(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody AccrualRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ledger.accrueDirectLaborCosts(new AccrueDirectLaborCostsCommand(
                        idempotencyKey, request.startDate(), request.endDate())));
    }

    @PostMapping("/after-sales-accruals")
    public ResponseEntity<CostAccrualRunRef> accrueAfterSalesCosts(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ledger.accrueAfterSalesCosts(
                        new AccrueAfterSalesCostsCommand(idempotencyKey)));
    }

    @PostMapping("/failures/{failureBusinessId}/retry")
    public ResponseEntity<CostAccrualRunRef> retryFailure(
            @PathVariable String failureBusinessId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody RetryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledger.retryFailure(
                new RetryCostFailureCommand(
                        idempotencyKey, failureBusinessId,
                        request.startDate(), request.endDate())));
    }

    @GetMapping("/failures")
    public List<CostLedgerQuery.CostFailureView> listFailures() {
        return queries.listPendingFailures();
    }

    @PostMapping("/entries/{entryBusinessId}/adjustments")
    public ResponseEntity<CostEntryRef> adjustCost(
            @PathVariable String entryBusinessId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody AdjustRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledger.adjust(
                new AdjustCostCommand(
                        idempotencyKey, entryBusinessId,
                        request.newAmount(), request.reasonText())));
    }

    @PostMapping("/entries/{entryBusinessId}/allocations")
    public ResponseEntity<CostEntryRef> allocateCost(
            @PathVariable String entryBusinessId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody AllocationRequest request) {
        List<AllocateCostCommand.AllocationRequest> allocations =
                request.allocations() == null
                        ? null
                        : request.allocations().stream()
                                .map(item -> new AllocateCostCommand.AllocationRequest(
                                        item.subjectType(), item.subjectBusinessId(), item.amount()))
                                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(ledger.allocate(
                new AllocateCostCommand(idempotencyKey, entryBusinessId, allocations)));
    }

    @GetMapping("/entries")
    public List<CostLedgerQuery.CostEntryView> listEntries(
            @RequestParam String subjectType,
            @RequestParam String subjectId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return queries.listEntries(subjectType, subjectId, startDate, endDate);
    }

    @GetMapping("/summary")
    public CostLedgerQuery.CostSubjectSummary getSummary(
            @RequestParam String subjectType,
            @RequestParam String subjectId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return queries.getSubjectCost(subjectType, subjectId, startDate, endDate);
    }

    /**
     * 经营费用入账；命令本身不带幂等键，使用平台公开幂等门面在同一事务内登记。
     */
    @PostMapping("/operating-expenses")
    public ResponseEntity<OperatingExpenseService.OperatingExpenseRef> recordOperatingExpense(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody OperatingExpenseRequest request) {
        var execution = commands.execute(
                "finance.costledger.record_operating_expense",
                idempotencyKey,
                request,
                () -> {
                    OperatingExpenseService.OperatingExpenseRef ref = expenses.record(
                            new OperatingExpenseCommand(
                                    request.expenseBusinessId(), request.cashFactBusinessId(),
                                    request.amount(), request.businessDate(),
                                    request.categoryCode()));
                    return new CommandOutcome("COST_ENTRY", ref.costEntryBusinessId());
                });
        return ResponseEntity
                .status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new OperatingExpenseService.OperatingExpenseRef(
                        request.expenseBusinessId(), execution.outcome().resultBusinessId()));
    }

    /**
     * 经营费用成对反向；公开反向方法不带幂等键，使用平台公开幂等门面包装。
     */
    @PostMapping("/operating-expenses/{expenseId}/reversal")
    public ResponseEntity<OperatingExpenseReversalResponse> reverseOperatingExpense(
            @PathVariable String expenseId,
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody(required = false) OperatingExpenseReversalRequest request) {
        String reasonText = request == null || request.reasonText() == null
                ? "" : request.reasonText();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("expenseBusinessId", expenseId);
        fingerprint.put("reasonText", reasonText);
        var execution = commands.execute(
                "finance.costledger.reverse_operating_expense",
                idempotencyKey,
                fingerprint,
                () -> new CommandOutcome(
                        "OPERATING_EXPENSE_REVERSAL",
                        expenses.reverse(expenseId, reasonText)));
        return ResponseEntity
                .status(execution.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new OperatingExpenseReversalResponse(
                        expenseId, execution.outcome().resultBusinessId()));
    }
}
