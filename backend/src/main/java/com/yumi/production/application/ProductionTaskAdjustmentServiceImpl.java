package com.yumi.production.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.DemandType;
import com.yumi.production.api.ProductionTaskAdjustmentParticipant;
import com.yumi.production.api.ShrinkUnverifiedCommand;
import com.yumi.production.api.ShrinkUnverifiedResult;
import com.yumi.production.api.TargetProcess;
import com.yumi.production.application.port.ReductionRepository;
import com.yumi.production.application.port.TaskRecord;
import com.yumi.production.application.port.TaskRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务缩减实现（订单减量/取消联动）。
 *
 * <p>只缩减目标来源尚未核验的分配：混合来源任务保留其他来源明细，已核验部分不动；
 * 人员计划负载与 {@code NEW_PRODUCT + MAKING} 的商品新制产能按本次缩减量等额释放，
 * 不跨日期搬移历史负载。
 */
public class ProductionTaskAdjustmentServiceImpl implements ProductionTaskAdjustmentParticipant {

    private final ReductionRepository reductions;
    private final TaskRepository tasks;
    private final DailyLoadService dailyLoads;
    private final NewProductCapacityService capacity;
    private final AuthorizationService authorization;

    public ProductionTaskAdjustmentServiceImpl(
            ReductionRepository reductions,
            TaskRepository tasks,
            DailyLoadService dailyLoads,
            NewProductCapacityService capacity,
            AuthorizationService authorization) {
        this.reductions = reductions;
        this.tasks = tasks;
        this.dailyLoads = dailyLoads;
        this.capacity = capacity;
        this.authorization = authorization;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ShrinkUnverifiedResult shrinkUnverified(ShrinkUnverifiedCommand command) {
        authorization.require(PermissionCodes.PRODUCTION_SCHEDULE, ResourceScope.GLOBAL);
        if (command == null || command.quantity() <= 0) {
            throw new ApiException(ApiErrorType.VALIDATION, "MISSING_SHRINK", "缩减命令与数量不能为空");
        }
        List<ReductionRepository.PendingItem> pending =
                reductions.findPendingItems(command.productionSourceBusinessId());
        int remaining = command.quantity();
        int releasedSeconds = 0;
        int cancelledItems = 0;

        Map<String, Integer> shrunkOfTask = new LinkedHashMap<>();
        Map<String, Integer> releasedSecondsOfTask = new LinkedHashMap<>();
        Map<String, Integer> releasedMakingOfProduct = new LinkedHashMap<>();

        for (var item : pending) {
            if (remaining <= 0) {
                break;
            }
            int shrink = Math.min(remaining, item.plannedQuantity());
            int nextPlanned = item.plannedQuantity() - shrink;
            remaining -= shrink;

            reductions.updateItemPlannedQuantity(
                    item.taskItemBusinessId(), nextPlanned, shrink);
            reductions.releaseHold(item.taskItemBusinessId(), nextPlanned);
            int seconds = shrink * item.itemStandardMinutes() * 60;
            releasedSeconds += seconds;
            if (nextPlanned == 0) {
                cancelledItems++;
            }
            shrunkOfTask.merge(item.taskBusinessId(), shrink, Integer::sum);
            releasedSecondsOfTask.merge(item.taskBusinessId(), seconds, Integer::sum);
            if (DemandType.valueOf(item.demandType()) == DemandType.NEW_PRODUCT) {
                releasedMakingOfProduct.merge(item.productBusinessId(), shrink, Integer::sum);
            }
        }
        // 未排产的数量没有任务占用，无需释放；只按实际缩减量释放来源预留
        int released = command.quantity() - remaining;
        if (released <= 0) {
            return new ShrinkUnverifiedResult(
                    command.productionSourceBusinessId(), 0, 0, 0, List.of());
        }
        reductions.releaseReservation(command.productionSourceBusinessId(), released);

        List<String> cancelledTasks = new ArrayList<>();
        for (var entry : shrunkOfTask.entrySet()) {
            TaskRecord task = tasks.findTask(entry.getKey()).orElseThrow(() -> new ApiException(
                    ApiErrorType.NOT_FOUND, "PRODUCTION_TASK_NOT_FOUND", "任务不存在：" + entry.getKey()));
            int releasedSecondsTotal = releasedSecondsOfTask.getOrDefault(entry.getKey(), 0);
            long newSeconds = Math.max(task.taskStandardSeconds() - releasedSecondsTotal, 0);
            int newPlanned = Math.max(task.plannedWorkQuantity() - entry.getValue(), 0);

            if (newPlanned <= 0 || newSeconds <= 0) {
                reductions.cancelTask(task.taskBusinessId());
                cancelledTasks.add(task.taskBusinessId());
            } else {
                reductions.updateTaskTotals(task.taskBusinessId(), newPlanned, newSeconds);
            }
            releaseLoads(task, releasedSecondsTotal);
            if (task.processCode() == TargetProcess.MAKING) {
                releaseCapacity(task, releasedMakingOfProduct);
            }
        }

        return new ShrinkUnverifiedResult(
                command.productionSourceBusinessId(), released, releasedSeconds,
                cancelledItems, cancelledTasks);
    }

    private void releaseLoads(TaskRecord task, int seconds) {
        if (seconds <= 0) {
            return;
        }
        for (String employeeId : tasks.findParticipantEmployeeIds(task.taskBusinessId())) {
            dailyLoads.releasePlan(employeeId, task.workDate(), seconds);
        }
    }

    private void releaseCapacity(TaskRecord task, Map<String, Integer> releasedByProduct) {
        for (var entry : releasedByProduct.entrySet()) {
            var usage = capacity.lockKeepingLimit(entry.getKey(), task.workDate());
            int release = Math.min(entry.getValue(), usage.reservedQuantity());
            if (release > 0) {
                capacity.releasePlan(usage, release);
            }
        }
    }
}
