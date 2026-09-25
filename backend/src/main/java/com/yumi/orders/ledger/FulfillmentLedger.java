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

    /**
     * 同步投影：按工序增减「有效计划占用」（阶段五计划创建/取消/调整调用）。
     * 计划占用不是履约事实，只影响订单侧排产状态派生。
     */
    public void applyPlanned(long orderItemId, String node, int quantity, boolean increase, String requestId) {
        repository.applyPlanned(orderItemId, node, quantity, increase, requestId);
    }

    /** 同步投影：累加「已核验处理」（全部工序合计，阶段五核验调用）。 */
    public void applyVerified(long orderItemId, int quantity, String requestId) {
        repository.applyVerified(orderItemId, quantity, requestId);
    }

    /** 同步投影：增减「累计有效发货」（发货确认/作废调用）。 */
    public void applyShipped(long orderItemId, int quantity, boolean increase, String requestId) {
        repository.applyShipped(orderItemId, quantity, increase, requestId);
    }

    /** 同步投影：增减「返工待安排」（返工来源创建/安排/取消时调用）。 */
    public void applyReworkPending(long orderItemId, int quantity, boolean increase, String requestId) {
        repository.applyReworkPending(orderItemId, quantity, increase, requestId);
    }

    /** 同步投影：增减「重做待安排」（重做来源创建/安排/取消时调用）。 */
    public void applyRemakePending(long orderItemId, int quantity, boolean increase, String requestId) {
        repository.applyRemakePending(orderItemId, quantity, increase, requestId);
    }

    /** 是否已被后续生产/核验/返工/余量/发货事实消费。 */
    public boolean hasDownstreamConsumption(long orderItemId, long afterEntryId) {
        return repository.hasDownstreamConsumption(orderItemId, afterEntryId);
    }

    /**
     * 锁定该明细的履约投影行（`SELECT ... FOR UPDATE`）：生产核验等命令在重算「当前可执行数量」前
     * 必须先锁它，避免同一明细同一工序的两个并发核验各自通过上限校验而超验。
     * 锁定顺序见施工文档 §8：履约余额 → 计划 → 来源 → 预占。
     */
    public void lockBalance(long orderItemId) {
        repository.lockBalance(orderItemId);
    }
}
