package com.yumi.orders.aftersales.api.http;

import com.yumi.orders.aftersales.api.AfterSalesCostRef;
import com.yumi.orders.aftersales.api.AfterSalesCostQuery;
import com.yumi.orders.aftersales.api.AfterSalesCostService;
import com.yumi.orders.aftersales.api.ConfirmAfterSalesCostCommand;
import com.yumi.orders.aftersales.api.CorrectAfterSalesCostCommand;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.api.value.SettlementMoney;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 售后费用 HTTP 适配器（交付清单 A02）。
 *
 * <p>只调用售后费用公开命令，确认与更正都以费用来源事实表达；费用不等于现金，
 * 收款、退款与赔付入口归 F04。
 */
@RestController
@RequestMapping("/api/after-sales/costs")
public class AfterSalesCostController {

    private final AfterSalesCostService costs;
    private final AfterSalesCostQuery queries;

    public AfterSalesCostController(
            AfterSalesCostService costs, AfterSalesCostQuery queries) {
        this.costs = costs;
        this.queries = queries;
    }

    /** 售后成本确认请求体。 */
    public record ConfirmCostRequest(
            String caseBusinessId,
            String costCategory,
            SettlementMoney amount,
            LocalDate businessDate,
            String reasonText) {
    }

    /** 售后成本更正请求体；旧版本反向、新版本确认。 */
    public record CorrectCostRequest(
            String caseBusinessId,
            String sourceBusinessId,
            SettlementMoney amount,
            LocalDate businessDate,
            String reasonText) {
    }

    @GetMapping
    public java.util.List<com.yumi.orders.aftersales.api.AfterSalesCostView> list(
            @org.springframework.web.bind.annotation.RequestParam String caseBusinessId) {
        return queries.listByCase(caseBusinessId);
    }

    @PostMapping("/confirmations")
    public ResponseEntity<AfterSalesCostRef> confirmCost(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody ConfirmCostRequest request) {
        AfterSalesCostRef cost = costs.confirmCost(new ConfirmAfterSalesCostCommand(
                idempotencyKey,
                request.caseBusinessId(),
                request.costCategory(),
                request.amount(),
                request.businessDate(),
                request.reasonText()));
        return ResponseEntity.status(HttpStatus.CREATED).body(cost);
    }

    @PostMapping("/corrections")
    public AfterSalesCostRef correctCost(
            @RequestHeader(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody CorrectCostRequest request) {
        return costs.correctCost(new CorrectAfterSalesCostCommand(
                idempotencyKey,
                request.caseBusinessId(),
                request.sourceBusinessId(),
                request.amount(),
                request.businessDate(),
                request.reasonText()));
    }
}
