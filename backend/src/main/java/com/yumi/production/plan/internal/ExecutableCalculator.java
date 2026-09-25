package com.yumi.production.plan.internal;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前可执行量计算（任务 5.3/5.4，施工文档 §4.2）：
 * <pre>
 * 当前可执行量 = 工序有效流入 − 该工序已核验处理
 * </pre>
 * 再按「计划日期 + id」**升序依次分配**给该明细该工序的待执行计划（先到先得），
 * 保证多个待执行计划之间不重复占用同一份可执行量。计划创建、核验上限校验共用本计算。
 */
@Component
public class ExecutableCalculator {

    private final ProductionPlanRepository repository;

    public ExecutableCalculator(ProductionPlanRepository repository) {
        this.repository = repository;
    }

    /** 该明细该工序当前可执行总量（不小于 0）。 */
    public int executableTotal(long orderItemId, String node, int inflow, int verified) {
        return Math.max(0, inflow - verified);
    }

    /** 计划 id → 该计划当前可执行数量（按分配顺序扣减后的结果）。 */
    public Map<Long, Integer> allocate(long orderItemId, String node, int inflow, int verified) {
        int available = executableTotal(orderItemId, node, inflow, verified);
        var result = new LinkedHashMap<Long, Integer>();
        for (var plan : repository.pendingPlans(orderItemId, node)) {
            int own = Math.min(plan.quantity(), available);
            result.put(plan.id(), own);
            available -= own;
        }
        return result;
    }

    /** 指定计划的当前可执行数量；该计划不在待执行集合里时返回 0。 */
    public int executableFor(long orderItemId, String node, long planId, int inflow, int verified) {
        return allocate(orderItemId, node, inflow, verified).getOrDefault(planId, 0);
    }
}
