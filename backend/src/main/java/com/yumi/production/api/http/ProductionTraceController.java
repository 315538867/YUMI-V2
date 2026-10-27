package com.yumi.production.api.http;

import com.yumi.production.api.DispositionQuery;
import com.yumi.production.api.DispositionView;
import com.yumi.production.api.OrderProductionTrace;
import com.yumi.production.api.ProductionCorrectionFeedItem;
import com.yumi.production.api.ProductionCorrectionQuery;
import com.yumi.production.api.ProductionQuantityLedger;
import com.yumi.production.api.ProductionQuery;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生产追溯 HTTP 适配器（交付清单 N03）。
 *
 * <p>只读来源处置、需求数量账、订单项生产链路与更正 Feed；转线下移交目前没有公开查询
 * 方法，不在此伪造，也不修改已退出实物。
 */
@RestController
@RequestMapping("/api/production")
public class ProductionTraceController {

    private final ProductionQuery production;

    private final ProductionCorrectionQuery corrections;

    private final DispositionQuery dispositions;

    public ProductionTraceController(
            ProductionQuery production,
            ProductionCorrectionQuery corrections,
            DispositionQuery dispositions) {
        this.production = production;
        this.corrections = corrections;
        this.dispositions = dispositions;
    }

    @GetMapping("/sources/{sourceBusinessId}")
    public List<DispositionView> sourceTrace(@PathVariable String sourceBusinessId) {
        return dispositions.findBySource(sourceBusinessId);
    }

    @GetMapping("/demands/{demandBusinessId}")
    public ProductionQuantityLedger demandTrace(@PathVariable String demandBusinessId) {
        return production.ledgerOfDemand(demandBusinessId);
    }

    @GetMapping("/orders/{orderItemBusinessId}/trace")
    public OrderProductionTrace orderTrace(@PathVariable String orderItemBusinessId) {
        return production.traceOfOrderItem(orderItemBusinessId);
    }

    @GetMapping("/traces")
    public List<OrderProductionTrace> recentTraces(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return production.recentTraces(offset, limit);
    }

    @GetMapping("/offline-transfers")
    public java.util.List<com.yumi.production.api.OfflineTransferView> offlineTransfers(
            @RequestParam String productionSourceBusinessId) {
        return production.offlineTransfersOfSource(productionSourceBusinessId);
    }

    @GetMapping("/corrections")
    public List<ProductionCorrectionFeedItem> corrections(
            @RequestParam(required = false) String correctionCommandBusinessId,
            @RequestParam(defaultValue = "0") long feedSequence,
            @RequestParam(defaultValue = "50") int limit) {
        if (correctionCommandBusinessId != null && !correctionCommandBusinessId.isBlank()) {
            return corrections.itemsOf(correctionCommandBusinessId);
        }
        return corrections.listAfter(feedSequence, limit);
    }
}
