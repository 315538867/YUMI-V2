package com.yumi.production.capacity;

import com.yumi.production.task.internal.ProductionTaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 正常产能校验（阶段五 5.4）：`NORMAL` 正常来源明细按「产品 + 任务日期 + 工序」汇总计划数量，
 * 不得超过该产品 `mold_quantity × daily_batch_limit` 派生的每日最大产能。
 *
 * <p>历史已核验明细仍占用该日期的历史资源（核验不释放排班资源），只有取消明细才释放；
 * `REWORK`、超额预占与阶段八售后来源明细都不占用该产能。
 * 写命令必须使用锁定版本（`FOR UPDATE`）在事务内重算，避免并发创建各自通过校验。
 */
@Service
public class ProductionCapacityService {

    private final ProductionTaskRepository repository;

    public ProductionCapacityService(ProductionTaskRepository repository) {
        this.repository = repository;
    }

    /** 产能余额：每日最大产能、已占用与剩余。 */
    public record CapacityUsage(int dailyMaxCapacity, int usedNormalQuantity, int remainingNormalQuantity) {
    }

    /** 只读余额（列表/详情展示）。 */
    public CapacityUsage usage(long productId, LocalDate taskDate, String node, int moldQuantity,
                               int dailyBatchLimit) {
        return of(moldQuantity * dailyBatchLimit,
                repository.normalCapacityUsage(productId, taskDate, node));
    }

    /** 锁定余额（创建命令内调用）：先锁定该产品/日期/工序上的有效计划行再汇总。 */
    public CapacityUsage lockedUsage(long productId, LocalDate taskDate, String node, int moldQuantity,
                                     int dailyBatchLimit) {
        return of(moldQuantity * dailyBatchLimit,
                repository.lockNormalCapacityUsage(productId, taskDate, node));
    }

    private static CapacityUsage of(int dailyMaxCapacity, int used) {
        return new CapacityUsage(dailyMaxCapacity, used, Math.max(0, dailyMaxCapacity - used));
    }
}
