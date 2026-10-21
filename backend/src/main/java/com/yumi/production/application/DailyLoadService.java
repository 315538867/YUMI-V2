package com.yumi.production.application;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.application.port.DailyLoadRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 员工日期负载：已核验负载与未核验计划占用分离（基线 11 §6.4.1）。
 *
 * <p>规则：
 * <ul>
 *   <li>传入工作量评估的占用固定为 {@code committed + reserved}；</li>
 *   <li>创建与调整只改未核验的 {@code reserved}；</li>
 *   <li>核验把完成数量转入 {@code committed}、把该明细全部计划量移出 {@code reserved}；
 *       未完成数量不进入已核验负载，只释放占用；</li>
 *   <li>取消只释放未执行部分，已核验部分留在原工作日期与主体下；</li>
 *   <li>核验与取消都不会让已完成工作从当天负载中消失。</li>
 * </ul>
 */
public class DailyLoadService {

    private final DailyLoadRepository repository;

    public DailyLoadService(DailyLoadRepository repository) {
        this.repository = repository;
    }

    /** 幂等建行并锁定单个员工的日期负载；调整时由调用方按稳定顺序锁定新旧键并集。 */
    public DayLoad lockDay(String employeeBusinessId, LocalDate workDate) {
        return toDayLoad(repository.lockDayLoad(employeeBusinessId, workDate));
    }

    /** 幂等建行并按稳定标识顺序锁定全部参与成员的日期负载。 */
    public Map<String, DayLoad> lockDays(Collection<String> employeeBusinessIds, LocalDate workDate) {
        Map<String, DayLoad> loads = new LinkedHashMap<>();
        employeeBusinessIds.stream().sorted().distinct().forEach(employeeId ->
                loads.put(employeeId, toDayLoad(repository.lockDayLoad(employeeId, workDate))));
        return loads;
    }

    /** 读取某员工某日负载；不存在视为零。 */
    public DayLoad load(String employeeBusinessId, LocalDate workDate) {
        return repository.findDayLoad(employeeBusinessId, workDate)
                .map(DailyLoadService::toDayLoad)
                .orElse(new DayLoad(employeeBusinessId, workDate, 0L, 0L, 0L));
    }

    /** 新增未核验计划占用（同一整体秒数计入每名快照成员）。 */
    public void reservePlan(Map<String, DayLoad> locked, long addedSeconds) {
        if (addedSeconds < 0) {
            throw new IllegalArgumentException("计划占用不能为负");
        }
        for (DayLoad load : locked.values()) {
            repository.updateDayLoad(
                    load.employeeBusinessId(), load.workDate(), load.committedSeconds(),
                    load.reservedSeconds() + addedSeconds);
        }
    }

    /** 只释放未核验计划占用（调整或取消未执行部分）。 */
    public void releasePlan(String employeeBusinessId, LocalDate workDate, long releasedSeconds) {
        DayLoad current = lockOne(employeeBusinessId, workDate);
        if (releasedSeconds > current.reservedSeconds()) {
            throw new IllegalStateException(
                    "释放计划占用超过当前未核验占用：" + employeeBusinessId + "/" + workDate);
        }
        repository.updateDayLoad(
                employeeBusinessId, workDate, current.committedSeconds(),
                current.reservedSeconds() - releasedSeconds);
    }

    /**
     * 核验：把完成工作量转入已核验负载，并移除该明细的全部计划占用。
     *
     * <p>{@code plannedSeconds} 是该明细原本占用的计划秒数；{@code completedSeconds} 是按同一
     * 冻结标准计算的完成工作量。未完成部分只释放占用，不进入已核验负载。
     */
    public void movePlanToCommitted(
            String employeeBusinessId, LocalDate workDate, long plannedSeconds, long completedSeconds) {
        DayLoad current = lockOne(employeeBusinessId, workDate);
        if (plannedSeconds > current.reservedSeconds()) {
            throw new IllegalStateException(
                    "计划占用不足，无法核验：" + employeeBusinessId + "/" + workDate);
        }
        repository.updateDayLoad(
                employeeBusinessId, workDate,
                current.committedSeconds() + completedSeconds,
                current.reservedSeconds() - plannedSeconds);
    }

    /** 取消：整体释放计划占用，已核验部分保留。 */
    public void cancelPlan(String employeeBusinessId, LocalDate workDate, long plannedSeconds) {
        releasePlan(employeeBusinessId, workDate, plannedSeconds);
    }

    private DayLoad lockOne(String employeeBusinessId, LocalDate workDate) {
        return toDayLoad(repository.lockDayLoad(employeeBusinessId, workDate));
    }

    private static DayLoad toDayLoad(DailyLoadRepository.LoadRecord record) {
        return new DayLoad(
                record.employeeBusinessId(), record.workDate(), record.committedSeconds(),
                record.reservedSeconds(), record.rowVersion());
    }

    /** 员工日期负载快照。 */
    public record DayLoad(
            String employeeBusinessId, LocalDate workDate, long committedSeconds,
            long reservedSeconds, long rowVersion) {

        /** 传入工作量评估的占用：已核验 + 未核验预留。 */
        public long occupiedSeconds() {
            return committedSeconds + reservedSeconds;
        }
    }

    /** 供调用方拒绝不可能的释放：语义化错误，而不是静默截断。 */
    public static ApiException inconsistent(String message) {
        return new ApiException(ApiErrorType.SYSTEM_FAILURE, "PRODUCTION_LOAD_INCONSISTENT", message);
    }
}
