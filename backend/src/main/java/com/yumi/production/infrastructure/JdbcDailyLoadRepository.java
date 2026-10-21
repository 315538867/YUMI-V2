package com.yumi.production.infrastructure;

import com.yumi.production.application.port.DailyLoadRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 员工日期负载、商品日期产能与任务槽位持久化（MySQL）。 */
@Repository
public class JdbcDailyLoadRepository implements DailyLoadRepository {

    private final JdbcClient jdbc;

    public JdbcDailyLoadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LoadRecord lockDayLoad(String employeeBusinessId, LocalDate workDate) {
        jdbc.sql("""
                        INSERT INTO production_employee_day_loads (
                            employee_business_id, work_date, committed_standard_seconds,
                            reserved_standard_seconds, row_version)
                        VALUES (:employeeId, :workDate, 0, 0, 1)
                        ON DUPLICATE KEY UPDATE row_version = row_version
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .update();
        return jdbc.sql("""
                        SELECT employee_business_id, work_date, committed_standard_seconds,
                               reserved_standard_seconds, row_version
                        FROM production_employee_day_loads
                        WHERE employee_business_id = :employeeId AND work_date = :workDate
                        FOR UPDATE
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .query((resultSet, rowNumber) -> new LoadRecord(
                        resultSet.getString("employee_business_id"),
                        resultSet.getObject("work_date", LocalDate.class),
                        resultSet.getLong("committed_standard_seconds"),
                        resultSet.getLong("reserved_standard_seconds"),
                        resultSet.getLong("row_version")))
                .single();
    }

    @Override
    public Optional<LoadRecord> findDayLoad(String employeeBusinessId, LocalDate workDate) {
        return jdbc.sql("""
                        SELECT employee_business_id, work_date, committed_standard_seconds,
                               reserved_standard_seconds, row_version
                        FROM production_employee_day_loads
                        WHERE employee_business_id = :employeeId AND work_date = :workDate
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .query((resultSet, rowNumber) -> new LoadRecord(
                        resultSet.getString("employee_business_id"),
                        resultSet.getObject("work_date", LocalDate.class),
                        resultSet.getLong("committed_standard_seconds"),
                        resultSet.getLong("reserved_standard_seconds"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public void updateDayLoad(
            String employeeBusinessId, LocalDate workDate, long committed, long reserved) {
        jdbc.sql("""
                        UPDATE production_employee_day_loads
                        SET committed_standard_seconds = :committed,
                            reserved_standard_seconds = :reserved, row_version = row_version + 1
                        WHERE employee_business_id = :employeeId AND work_date = :workDate
                        """)
                .param("committed", committed)
                .param("reserved", reserved)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .update();
    }

    @Override
    public CapacityRecord lockCapacity(
            String productBusinessId, LocalDate workDate, int capacityLimit, long productContentVersion) {
        jdbc.sql("""
                        INSERT INTO production_product_day_capacity_usages (
                            product_business_id, work_date, committed_new_making_quantity,
                            reserved_new_making_quantity, capacity_limit, product_content_version, row_version)
                        VALUES (:productId, :workDate, 0, 0, :limit, :contentVersion, 1)
                        ON DUPLICATE KEY UPDATE
                            capacity_limit = :limit,
                            product_content_version = :contentVersion,
                            row_version = row_version + 1
                        """)
                .param("productId", productBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .param("limit", capacityLimit)
                .param("contentVersion", productContentVersion)
                .update();
        return jdbc.sql("""
                        SELECT product_business_id, work_date, committed_new_making_quantity,
                               reserved_new_making_quantity, capacity_limit, product_content_version, row_version
                        FROM production_product_day_capacity_usages
                        WHERE product_business_id = :productId AND work_date = :workDate
                        FOR UPDATE
                        """)
                .param("productId", productBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .query((resultSet, rowNumber) -> new CapacityRecord(
                        resultSet.getString("product_business_id"),
                        resultSet.getObject("work_date", LocalDate.class),
                        resultSet.getInt("committed_new_making_quantity"),
                        resultSet.getInt("reserved_new_making_quantity"),
                        resultSet.getInt("capacity_limit"),
                        resultSet.getLong("product_content_version"),
                        resultSet.getLong("row_version")))
                .single();
    }

    @Override
    public Optional<CapacityRecord> findCapacity(String productBusinessId, LocalDate workDate) {
        return jdbc.sql("""
                        SELECT product_business_id, work_date, committed_new_making_quantity,
                               reserved_new_making_quantity, capacity_limit, product_content_version, row_version
                        FROM production_product_day_capacity_usages
                        WHERE product_business_id = :productId AND work_date = :workDate
                        """)
                .param("productId", productBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .query((resultSet, rowNumber) -> new CapacityRecord(
                        resultSet.getString("product_business_id"),
                        resultSet.getObject("work_date", LocalDate.class),
                        resultSet.getInt("committed_new_making_quantity"),
                        resultSet.getInt("reserved_new_making_quantity"),
                        resultSet.getInt("capacity_limit"),
                        resultSet.getLong("product_content_version"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public void updateCapacity(
            String productBusinessId, LocalDate workDate, int committed, int reserved,
            int capacityLimit, long productContentVersion) {
        jdbc.sql("""
                        UPDATE production_product_day_capacity_usages
                        SET committed_new_making_quantity = :committed,
                            reserved_new_making_quantity = :reserved,
                            capacity_limit = :limit, product_content_version = :contentVersion,
                            row_version = row_version + 1
                        WHERE product_business_id = :productId AND work_date = :workDate
                        """)
                .param("committed", committed)
                .param("reserved", reserved)
                .param("limit", capacityLimit)
                .param("contentVersion", productContentVersion)
                .param("productId", productBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .update();
    }

    @Override
    public Optional<String> findWorkMode(String employeeBusinessId, LocalDate workDate) {
        return jdbc.sql("""
                        SELECT work_mode FROM production_employee_day_work_modes
                        WHERE employee_business_id = :employeeId AND work_date = :workDate
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .query(String.class)
                .optional();
    }

    @Override
    public void upsertWorkMode(String employeeBusinessId, LocalDate workDate, String workMode) {
        jdbc.sql("""
                        INSERT INTO production_employee_day_work_modes (
                            employee_business_id, work_date, work_mode, retained_task_count, row_version)
                        VALUES (:employeeId, :workDate, :workMode, 0, 1)
                        ON DUPLICATE KEY UPDATE
                            work_mode = :workMode, row_version = row_version + 1
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .param("workMode", workMode)
                .update();
    }

    @Override
    public void releaseWorkMode(String employeeBusinessId, LocalDate workDate) {
        jdbc.sql("""
                        DELETE FROM production_employee_day_work_modes
                        WHERE employee_business_id = :employeeId AND work_date = :workDate
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .update();
    }

    @Override
    public Optional<SlotRecord> findSlot(String employeeBusinessId, LocalDate workDate, String slotType) {
        return jdbc.sql("""
                        SELECT employee_business_id, work_date, slot_type, task_business_id, status, row_version
                        FROM production_employee_daily_task_slots
                        WHERE employee_business_id = :employeeId AND work_date = :workDate
                          AND slot_type = :slotType
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .param("slotType", slotType)
                .query((resultSet, rowNumber) -> new SlotRecord(
                        resultSet.getString("employee_business_id"),
                        resultSet.getObject("work_date", LocalDate.class),
                        resultSet.getString("slot_type"),
                        resultSet.getString("task_business_id"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .optional();
    }

    @Override
    public void upsertSlot(
            String employeeBusinessId, LocalDate workDate, String slotType,
            String taskBusinessId, String status) {
        jdbc.sql("""
                        INSERT INTO production_employee_daily_task_slots (
                            employee_business_id, work_date, slot_type, task_business_id, status, row_version)
                        VALUES (:employeeId, :workDate, :slotType, :taskId, :status, 1)
                        ON DUPLICATE KEY UPDATE
                            task_business_id = :taskId, status = :status, row_version = row_version + 1
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .param("slotType", slotType)
                .param("taskId", taskBusinessId)
                .param("status", status)
                .update();
    }

    @Override
    public void updateSlotStatusForTask(
            String employeeBusinessId, LocalDate workDate, String slotType,
            String taskBusinessId, String status) {
        jdbc.sql("""
                        UPDATE production_employee_daily_task_slots
                        SET status = :status, row_version = row_version + 1
                        WHERE employee_business_id = :employeeId AND work_date = :workDate
                          AND slot_type = :slotType AND task_business_id = :taskId
                        """)
                .param("status", status)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .param("slotType", slotType)
                .param("taskId", taskBusinessId)
                .update();
    }

    @Override
    public List<SlotRecord> findSlots(String employeeBusinessId, LocalDate workDate) {
        return jdbc.sql("""
                        SELECT employee_business_id, work_date, slot_type, task_business_id, status, row_version
                        FROM production_employee_daily_task_slots
                        WHERE employee_business_id = :employeeId AND work_date = :workDate
                        """)
                .param("employeeId", employeeBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .query((resultSet, rowNumber) -> new SlotRecord(
                        resultSet.getString("employee_business_id"),
                        resultSet.getObject("work_date", LocalDate.class),
                        resultSet.getString("slot_type"),
                        resultSet.getString("task_business_id"),
                        resultSet.getString("status"),
                        resultSet.getLong("row_version")))
                .list();
    }

    @Override
    public void insertActiveSubject(
            String taskBusinessId, String subjectType, String subjectBusinessId, LocalDate workDate) {
        jdbc.sql("""
                        INSERT INTO production_active_subject_references (
                            task_business_id, subject_type, subject_business_id, work_date, status)
                        VALUES (:taskId, :subjectType, :subjectId, :workDate, 'ACTIVE')
                        ON DUPLICATE KEY UPDATE
                            subject_type = :subjectType, subject_business_id = :subjectId,
                            work_date = :workDate, status = 'ACTIVE'
                        """)
                .param("taskId", taskBusinessId)
                .param("subjectType", subjectType)
                .param("subjectId", subjectBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .update();
    }

    @Override
    public void insertActiveParticipant(
            String taskBusinessId, String employeeBusinessId, String subjectType,
            String subjectBusinessId, LocalDate workDate, long occupiedStandardSeconds) {
        jdbc.sql("""
                        INSERT INTO production_active_participant_references (
                            task_business_id, employee_business_id, subject_type, subject_business_id,
                            work_date, occupied_standard_seconds, status)
                        VALUES (:taskId, :employeeId, :subjectType, :subjectId, :workDate, :seconds, 'ACTIVE')
                        ON DUPLICATE KEY UPDATE
                            subject_type = :subjectType, subject_business_id = :subjectId,
                            work_date = :workDate, occupied_standard_seconds = :seconds,
                            status = 'ACTIVE'
                        """)
                .param("taskId", taskBusinessId)
                .param("employeeId", employeeBusinessId)
                .param("subjectType", subjectType)
                .param("subjectId", subjectBusinessId)
                .param("workDate", Date.valueOf(workDate))
                .param("seconds", occupiedStandardSeconds)
                .update();
    }

    @Override
    public void releaseActiveParticipant(String taskBusinessId, String employeeBusinessId) {
        jdbc.sql("""
                        UPDATE production_active_participant_references SET status = 'RELEASED'
                        WHERE task_business_id = :taskId AND employee_business_id = :employeeId
                        """)
                .param("taskId", taskBusinessId)
                .param("employeeId", employeeBusinessId)
                .update();
    }
}
