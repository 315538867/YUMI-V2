package com.yumi.production.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 员工日期负载、商品日期产能与任务槽位持久化端口。 */
public interface DailyLoadRepository {

    /** 幂等建行并锁定员工日期负载。 */
    LoadRecord lockDayLoad(String employeeBusinessId, LocalDate workDate);

    Optional<LoadRecord> findDayLoad(String employeeBusinessId, LocalDate workDate);

    void updateDayLoad(String employeeBusinessId, LocalDate workDate, long committed, long reserved);

    /** 幂等建行并锁定商品日期产能。 */
    CapacityRecord lockCapacity(String productBusinessId, LocalDate workDate, int capacityLimit,
            long productContentVersion);

    Optional<CapacityRecord> findCapacity(String productBusinessId, LocalDate workDate);

    void updateCapacity(String productBusinessId, LocalDate workDate, int committed, int reserved,
            int capacityLimit, long productContentVersion);

    /** 员工日期工作模式；不存在返回空。 */
    Optional<String> findWorkMode(String employeeBusinessId, LocalDate workDate);

    void upsertWorkMode(String employeeBusinessId, LocalDate workDate, String workMode);

    /** 未核验任务取消且无保留槽位时移除当日工作模式。 */
    void releaseWorkMode(String employeeBusinessId, LocalDate workDate);

    Optional<SlotRecord> findSlot(String employeeBusinessId, LocalDate workDate, String slotType);

    void upsertSlot(String employeeBusinessId, LocalDate workDate, String slotType,
            String taskBusinessId, String status);

    /** 只调整该任务持有的槽位，避免误改同日其他任务。 */
    void updateSlotStatusForTask(String employeeBusinessId, LocalDate workDate, String slotType,
            String taskBusinessId, String status);

    List<SlotRecord> findSlots(String employeeBusinessId, LocalDate workDate);

    /** 记录活动主体与参与人引用；调整时同一任务原地更新到新主体、日期与秒数。 */
    void insertActiveSubject(String taskBusinessId, String subjectType, String subjectBusinessId, LocalDate workDate);

    void insertActiveParticipant(String taskBusinessId, String employeeBusinessId, String subjectType,
            String subjectBusinessId, LocalDate workDate, long occupiedStandardSeconds);

    /** 调整后不再参与的成员保留引用历史，只置为已释放。 */
    void releaseActiveParticipant(String taskBusinessId, String employeeBusinessId);

    /** 员工日期负载行。 */
    record LoadRecord(
            String employeeBusinessId, LocalDate workDate, long committedSeconds, long reservedSeconds,
            long rowVersion) {
    }

    /** 商品日期产能行。 */
    record CapacityRecord(
            String productBusinessId, LocalDate workDate, int committedQuantity, int reservedQuantity,
            int capacityLimit, long productContentVersion, long rowVersion) {
    }

    /** 日任务槽位。 */
    record SlotRecord(
            String employeeBusinessId, LocalDate workDate, String slotType, String taskBusinessId,
            String status, long rowVersion) {
    }
}
