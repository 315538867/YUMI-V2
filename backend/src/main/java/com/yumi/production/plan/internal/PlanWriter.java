package com.yumi.production.plan.internal;

import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 生产计划落库（阶段五）：分配 `PN` 编号、写入计划、登记订单侧「有效计划占用」投影。
 * 正常计划（5.2）、返工/重做计划（5.6/5.7）、超额计划（5.10）共用本写入器，
 * 保证计划占用投影与计划行始终同事务、同口径。
 */
@Component
public class PlanWriter {

    private final ProductionPlanRepository repository;
    private final FulfillmentLedger ledger;
    private final SequenceAllocator sequenceAllocator;

    public PlanWriter(ProductionPlanRepository repository, FulfillmentLedger ledger,
                      SequenceAllocator sequenceAllocator) {
        this.repository = repository;
        this.ledger = ledger;
        this.sequenceAllocator = sequenceAllocator;
    }

    public long insert(String planType, long orderId, long orderItemId, String node, LocalDate planDate,
                       long employeeId, String employeeName, int quantity, String sourceType, long sourceId,
                       long sourceLineId, String note, String requestId) {
        long id = write(planType, orderId, orderItemId, node, planDate, employeeId, employeeName, quantity,
                sourceType, sourceId, sourceLineId, note, requestId);
        ledger.applyPlanned(orderItemId, node, quantity, true, requestId);
        return id;
    }

    /**
     * 售后来源计划（任务 8.4/8.5）：只写计划行，**不登记订单侧计划占用**——
     * 售后返工/补发数量不属于订单工序需求，占用由售后来源行的 `arranged_quantity` 承担。
     */
    public long insertForAfterSales(String planType, long orderId, long orderItemId, String node,
                                    LocalDate planDate, long employeeId, String employeeName, int quantity,
                                    String sourceType, long sourceId, long sourceLineId, String note,
                                    String requestId) {
        return write(planType, orderId, orderItemId, node, planDate, employeeId, employeeName, quantity,
                sourceType, sourceId, sourceLineId, note, requestId);
    }

    private long write(String planType, long orderId, long orderItemId, String node, LocalDate planDate,
                       long employeeId, String employeeName, int quantity, String sourceType, long sourceId,
                       long sourceLineId, String note, String requestId) {
        var planNo = SequenceAllocator.format("PN", sequenceAllocator.next("production_plans"), 6);
        return repository.insert(planNo, planType, orderId, orderItemId, node, planDate, employeeId,
                employeeName, quantity, sourceType, sourceId, sourceLineId, note, requestId);
    }

    /** 取消待执行计划时释放计划占用。 */
    public void releasePlanned(long orderItemId, String node, int quantity, String requestId) {
        ledger.applyPlanned(orderItemId, node, quantity, false, requestId);
    }
}
