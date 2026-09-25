package com.yumi.orders.ledger;

import com.yumi.orders.aftersales.internal.AfterSalesRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 售后补发台账的跨模块结果接口（任务 8.6，依据 `domain-and-quantity-model.md` §2.4）：
 * 库存与生产模块通过本接口向售后补发台账登记来源事实，不直接访问售后模块的内部包。
 * 事实只追加；可补发数量由台账汇总派生（不落缓存列）。
 */
@Service
public class AfterSalesLedger {

    /** 库存接入来源：来源记录为库存流水（头 + 行），便于按流水追溯与冲销。 */
    public static final String SOURCE_INVENTORY = "INVENTORY";

    private final AfterSalesRepository repository;

    public AfterSalesLedger(AfterSalesRepository repository) {
        this.repository = repository;
    }

    /**
     * 登记一条售后可补发入库事实（库存领用接入 / 售后返工合格 / 售后生产合格），返回事实 id。
     * 调用方负责在写事实的同一事务内完成对应的库存或生产动作。
     */
    public long registerInflow(long afterSalesItemId, String entryType, int quantity, String sourceType,
                               long sourceId, long sourceLineId, LocalDate businessDate,
                               String operatorUsername, String note, String requestId) {
        return repository.insertEntry(afterSalesItemId, entryType, "IN", quantity, sourceType, sourceId,
                sourceLineId, businessDate, operatorUsername, note, requestId);
    }

    /** 登记一条售后补发台账的冲销事实（与库存流水冲销同事务），数量为正、方向为 OUT。 */
    public long registerReversal(long afterSalesItemId, int quantity, String sourceType, long sourceId,
                                 long sourceLineId, LocalDate businessDate, String operatorUsername,
                                 String note, String requestId) {
        return repository.insertEntry(afterSalesItemId, "REVERSAL", "OUT", quantity, sourceType, sourceId,
                sourceLineId, businessDate, operatorUsername, note, requestId);
    }
}
