package com.yumi.production.application;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.product.api.ProductSnapshotRef;
import com.yumi.product.api.ProductSnapshotService;
import com.yumi.production.application.port.DailyLoadRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品单日新制产能守卫（基线 11 §5.3、§6.6）。
 *
 * <p>正常状态下不变量为 {@code committed + reserved <= capacity_limit}；商品上限被下调到低于既有
 * 已提交数量时允许暂时违反，但此时任何新增或扩大安排都必须被拒绝（基线 11 §6.6）。
 *
 * <p>规则：
 * <ul>
 *   <li>只有 {@code NEW_PRODUCT + MAKING} 的完成与计划数量占用新制产能；修补、包装与其他任务
 *       处理既有制品，不占模具新制产能；</li>
 *   <li>产能上限与商品内容版本在日期行锁内刷新，历史已提交数量不回算、不清零；</li>
 *   <li>若新上限小于既有已提交数量，只拒绝新增或扩大安排，并返回明确的产能配置冲突，
 *       不追溯性地使已完成事实失效；</li>
 *   <li>核验把计划的完成数量从 {@code reserved} 转入 {@code committed}，未完成数量只释放
 *       {@code reserved}；取消或调整未核验任务只释放 {@code reserved}。</li>
 * </ul>
 */
public class NewProductCapacityService {

    private final DailyLoadRepository repository;

    private final ProductSnapshotService productSnapshots;

    public NewProductCapacityService(
            DailyLoadRepository repository, ProductSnapshotService productSnapshots) {
        this.repository = repository;
        this.productSnapshots = productSnapshots;
    }

    /** 按产品或稳定标识顺序逐项锁定产能行，返回锁定结果（尚未增加占用）。 */
    public Map<String, CapacityLock> lock(
            Map<String, Integer> newMakingQuantityByProduct, LocalDate workDate) {
        Map<String, CapacityLock> locks = new LinkedHashMap<>();
        newMakingQuantityByProduct.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ProductSnapshotRef snapshot =
                            productSnapshots.acquireProductSnapshot(entry.getKey());
                    int limit = productSnapshots.resolveProduct(snapshot).dailyCapacity();
                    locks.put(entry.getKey(), lockOne(
                            entry.getKey(), workDate, limit, snapshot.sourceContentVersion()));
                });
        return locks;
    }

    /** 单产品锁定；上限取自商品当前快照。 */
    public CapacityLock lockOne(
            String productBusinessId, LocalDate workDate, int capacityLimit, long productContentVersion) {
        var existing = repository.lockCapacity(
                productBusinessId, workDate, capacityLimit, productContentVersion);
        return new CapacityLock(
                productBusinessId, workDate, existing.committedQuantity(), existing.reservedQuantity(),
                capacityLimit, productContentVersion, existing.rowVersion());
    }

    /**
     * 沿用当前行已保存的上限与商品版本锁定产能行（核验、取消使用）。
     *
     * <p>核验不得刷新上限：商品上限可能已被下调，此刻只按既有行结账，不改变历史。
     */
    public CapacityLock lockKeepingLimit(String productBusinessId, LocalDate workDate) {
        var existing = repository.findCapacity(productBusinessId, workDate)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_CAPACITY_INCONSISTENT",
                        "商品日期产能行缺失：" + productBusinessId));
        return lockOne(productBusinessId, workDate, existing.capacityLimit(), existing.productContentVersion());
    }

    /**
     * 校验并登记未核验计划占用。
     *
     * <p>超出上限或既有已提交数量已超过新上限时抛出明确错误并回滚整笔安排。
     */
    public void reservePlan(CapacityLock lock, int quantity) {
        int used = lock.committedQuantity() + lock.reservedQuantity();
        if (quantity <= 0) {
            throw new IllegalArgumentException("计划占用数量必须大于 0");
        }
        if (used > lock.capacityLimit()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CAPACITY_CONFIG_CONFLICT",
                    "商品单日产能上限 " + lock.capacityLimit() + " 已低于既有已用 " + used
                            + "，不能新增或扩大安排，请先调整商品产能配置");
        }
        if (used + quantity > lock.capacityLimit()) {
            throw new ApiException(
                    ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_CAPACITY_EXCEEDED",
                    "超出商品单日产能：已用 " + used + "，本次 " + quantity + "，上限 "
                            + lock.capacityLimit());
        }
        repository.updateCapacity(
                lock.productBusinessId(), lock.workDate(), lock.committedQuantity(),
                lock.reservedQuantity() + quantity, lock.capacityLimit(), lock.productContentVersion());
    }

    /** 核验：计划的完成数量转入已用产能，未完成数量只释放预留。 */
    public void movePlanToCommitted(
            CapacityLock lock, int plannedQuantity, int completedQuantity) {
        if (plannedQuantity > lock.reservedQuantity()) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_CAPACITY_INCONSISTENT",
                    "计划占用不足，无法核验：" + lock.productBusinessId());
        }
        repository.updateCapacity(
                lock.productBusinessId(), lock.workDate(),
                lock.committedQuantity() + completedQuantity,
                lock.reservedQuantity() - plannedQuantity,
                lock.capacityLimit(), lock.productContentVersion());
    }

    /** 取消或调整：只释放未核验预留。 */
    public void releasePlan(CapacityLock lock, int quantity) {
        if (quantity > lock.reservedQuantity()) {
            throw new ApiException(
                    ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_CAPACITY_INCONSISTENT",
                    "释放预留超过当前预留：" + lock.productBusinessId());
        }
        repository.updateCapacity(
                lock.productBusinessId(), lock.workDate(), lock.committedQuantity(),
                lock.reservedQuantity() - quantity, lock.capacityLimit(), lock.productContentVersion());
    }

    /** 当前可安排新制产能。 */
    public int available(CapacityLock lock) {
        return lock.capacityLimit() - lock.committedQuantity() - lock.reservedQuantity();
    }

    /** 已锁定的产能行。 */
    public record CapacityLock(
            String productBusinessId, LocalDate workDate, int committedQuantity, int reservedQuantity,
            int capacityLimit, long productContentVersion, long rowVersion) {

        public int usedQuantity() {
            return committedQuantity + reservedQuantity;
        }
    }
}
