package com.yumi.production.scrap;

import com.yumi.production.scrap.internal.ProductionQuantityReturnRepository;
import com.yumi.production.scrap.internal.ProductionQuantityReturnRow;
import com.yumi.production.scrap.internal.ScrapRecordRepository;
import com.yumi.production.scrap.internal.ScrapRecordRow;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报废事实与数量回转查询（阶段五 5.12）：两者都是不可变事实，只提供只读查询。
 * 报废由核验事务写入并一对一生成同工序回转，不产生替代类型、不增加订单需求。
 */
@RestController
@RequestMapping("/api")
public class ProductionScrapController {

    private final ScrapRecordRepository scrapRepository;
    private final ProductionQuantityReturnRepository returnRepository;

    public ProductionScrapController(ScrapRecordRepository scrapRepository,
                                     ProductionQuantityReturnRepository returnRepository) {
        this.scrapRepository = scrapRepository;
        this.returnRepository = returnRepository;
    }

    /** 报废事实视图：保留发生工序、数量、原因、操作人与时间。 */
    public record ScrapView(long id, long verificationId, long taskItemId, long orderId, long orderItemId,
                            long productId, String node, int scrapQuantity, String reason,
                            String operatorUsername, LocalDateTime recordedAt) {
    }

    /** 数量回转视图：回转总量、已分配量与可分配余额。 */
    public record ReturnView(long id, long scrapRecordId, long orderId, long orderItemId, long productId,
                             String node, int returnedQuantity, int allocatedQuantity, int availableQuantity) {
    }

    @GetMapping("/production-scraps")
    public List<ScrapView> listScraps(@RequestParam(required = false) Long orderItemId,
                                      @RequestParam(required = false) String node) {
        return scrapRepository.find(orderItemId, node).stream().map(ProductionScrapController::toView).toList();
    }

    @GetMapping("/production-scraps/{id}")
    public ScrapView getScrap(@PathVariable long id) {
        return scrapRepository.findById(id).map(ProductionScrapController::toView)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "报废事实不存在"));
    }

    @GetMapping("/production-quantity-returns")
    public List<ReturnView> listReturns(@RequestParam(required = false) Long orderItemId,
                                        @RequestParam(required = false) String node) {
        return returnRepository.find(orderItemId, node).stream()
                .map(ProductionScrapController::toView).toList();
    }

    private static ScrapView toView(ScrapRecordRow row) {
        return new ScrapView(row.id(), row.verificationId(), row.taskItemId(), row.orderId(), row.orderItemId(),
                row.productId(), row.node(), row.scrapQuantity(), row.reason(), row.operatorUsername(),
                row.recordedAt());
    }

    private static ReturnView toView(ProductionQuantityReturnRow row) {
        return new ReturnView(row.id(), row.scrapRecordId(), row.orderId(), row.orderItemId(), row.productId(),
                row.node(), row.returnedQuantity(), row.allocatedQuantity(), row.availableQuantity());
    }
}
