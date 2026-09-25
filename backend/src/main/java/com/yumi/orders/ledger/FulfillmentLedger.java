package com.yumi.orders.ledger;

import com.yumi.orders.fulfillment.internal.FulfillmentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 订单履约台账的跨模块结果接口（任务 4.6/4.9，依据 `domain-and-quantity-model.md` §2.4）：
 * 库存与生产模块通过本接口向订单履约台账登记来源事实，不直接访问订单模块的内部包或实体。
 * 事实只追加；投影只允许在同一事务内随事实一起调整。
 */
@Service
public class FulfillmentLedger {

    private final FulfillmentRepository repository;

    public FulfillmentLedger(FulfillmentRepository repository) {
        this.repository = repository;
    }

    /** 登记一条入库性质的来源事实（库存领用接入、生产合格等），返回事实 id 供来源关联。 */
    public long registerInflow(long orderId, long orderItemId, String entryType, String node, int quantity,
                               String sourceType, long sourceId, long sourceLineId, LocalDate businessDate,
                               String operatorUsername, String note, String requestId) {
        return repository.insertEntryReturningId(orderId, orderItemId, entryType, node, "IN", quantity,
                sourceType, sourceId, sourceLineId, businessDate, operatorUsername, note, requestId);
    }

    /** 登记一条出库性质的来源事实（领用取消的反向记录、发货消耗等）。 */
    public long registerOutflow(long orderId, long orderItemId, String entryType, String node, int quantity,
                                String sourceType, long sourceId, long sourceLineId, LocalDate businessDate,
                                String operatorUsername, String note, String requestId) {
        return repository.insertEntryReturningId(orderId, orderItemId, entryType, node, "OUT", quantity,
                sourceType, sourceId, sourceLineId, businessDate, operatorUsername, note, requestId);
    }

    /** 同步投影：按目标节点增减工序流入或最终可发货。 */
    public void applyInflow(long orderItemId, String node, int quantity, boolean increase, String requestId) {
        repository.applyInflow(orderItemId, node, quantity, increase, requestId);
    }

    /** 是否已被后续生产/核验/返工/余量/发货事实消费。 */
    public boolean hasDownstreamConsumption(long orderItemId, long afterEntryId) {
        return repository.hasDownstreamConsumption(orderItemId, afterEntryId);
    }
}
