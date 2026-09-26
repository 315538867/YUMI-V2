package com.yumi.production.flow;

import com.yumi.orders.ledger.FulfillmentLedger;
import com.yumi.production.ProductionNodes;
import com.yumi.production.internal.OrderProductionReference;
import com.yumi.production.scrap.internal.ProductionQuantityReturnRepository;
import com.yumi.production.task.internal.ProductionTaskItemRow;
import com.yumi.production.task.internal.ProductionTaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产数量流转（阶段五 5.6/5.8）：明线计划与暗线事实分离。
 *
 * <pre>
 * 实际流入     = 上游实际合格流入（首道制作为订单实际制作缺口）+ 兼容库存接入 + 同工序报废回转余额
 * 当前可执行量 = 实际流入 − 已核验处理数量 − 其他待执行正常明细已分配的可执行数量
 * </pre>
 *
 * 上游计划数量、下游计划数量、订购数量与客户端提交的「可执行数量」都不能让明细变得可核验；
 * 等待上游是派生条件，不是失败占位状态。合格数量按冻结流程流向下一节点，
 * 捏毛装袋按订单确认时冻结的缝边数量分流到缝边剪袋与可发货，工序数量不得相加。
 */
@Service
public class ProductionFlowService {

    static final String ENTRY_QUALIFIED = "PRODUCTION_QUALIFIED";
    static final String SOURCE_PRODUCTION = "PRODUCTION";

    private final ProductionTaskRepository taskRepository;
    private final ProductionQuantityReturnRepository returnRepository;
    private final FulfillmentLedger ledger;

    public ProductionFlowService(ProductionTaskRepository taskRepository,
                                 ProductionQuantityReturnRepository returnRepository,
                                 FulfillmentLedger ledger) {
        this.taskRepository = taskRepository;
        this.returnRepository = returnRepository;
        this.ledger = ledger;
    }

    /** 明细的流入与可执行派生量。 */
    public record Inflow(int actualInflow, int verifiedProcessed, int executable, boolean waitingUpstream) {
    }

    /** 合格流入的流向：目标节点 + 数量。 */
    public record FlowView(String node, int quantity) {
    }

    /**
     * 计算某条**正常来源**明细的当前可执行量：同一订单明细 + 工序下按任务日期与明细 id 升序先到先得，
     * 只使用事实流入（上游合格/库存/同工序回转），不使用任何计划数量。
     * 返工明细的可执行上限来自返工来源余额，由核验服务直接读取来源，不并入正常流入。
     */
    public Inflow inflowFor(ProductionTaskItemRow item, OrderProductionReference.OrderItemContext context) {
        int actualInflow = Math.max(0, context.effectiveInflow(item.node())
                + returnRepository.availableByItemNode(item.orderItemId(), item.node()));
        int verified = taskRepository.verifiedByItemNode(List.of(item.orderItemId()))
                .getOrDefault(key(item.orderItemId(), item.node()), 0);
        int available = Math.max(0, actualInflow - verified);
        for (var candidate : taskRepository.findExecutableCandidates(item.orderItemId(), item.node())) {
            int own = Math.min(candidate.plannedQuantity(), available);
            available -= own;
            if (candidate.id().equals(item.id())) {
                return new Inflow(actualInflow, verified, own, own == 0);
            }
        }
        return new Inflow(actualInflow, verified, 0, true);
    }

    /**
     * 合格分流：制作 → 捏毛装袋；捏毛装袋按冻结缝边数量拆到缝边剪袋与可发货；缝边剪袋 → 可发货。
     * 缝边数量只认订单确认快照，不读当前商品默认值，也不把三道工序数量相加。
     */
    public List<FlowView> qualifiedFlows(String node, int qualified, OrderProductionReference.OrderItemContext context) {
        if (qualified <= 0) {
            return List.of();
        }
        if (ProductionNodes.PACKING_BAG.equals(node)) {
            int remainingSeam = Math.max(0, context.seamQuantity() - context.inflow(ProductionNodes.SEAM_CUTTING));
            int seam = Math.min(remainingSeam, qualified);
            var flows = new ArrayList<FlowView>();
            if (seam > 0) {
                flows.add(new FlowView(ProductionNodes.SEAM_CUTTING, seam));
            }
            if (qualified - seam > 0) {
                flows.add(new FlowView(ProductionNodes.SHIPPABLE, qualified - seam));
            }
            return flows;
        }
        var target = ProductionNodes.qualifiedTarget(node);
        return target == null ? List.of() : List.of(new FlowView(target, qualified));
    }

    /** 登记合格流入事实并同步履约投影（同一核验事务内调用）。 */
    public void registerQualified(ProductionTaskItemRow item, FlowView flow, LocalDate businessDate,
                                  String taskNo, String operatorUsername, String requestId) {
        ledger.registerInflow(item.orderId(), item.orderItemId(), ENTRY_QUALIFIED, flow.node(),
                flow.quantity(), SOURCE_PRODUCTION, item.id(), 0, businessDate, operatorUsername,
                "生产核验合格（" + taskNo + "）", requestId);
        ledger.applyInflow(item.orderItemId(), flow.node(), flow.quantity(), true, requestId);
    }

    private static String key(long orderItemId, String node) {
        return orderItemId + ":" + node;
    }
}
