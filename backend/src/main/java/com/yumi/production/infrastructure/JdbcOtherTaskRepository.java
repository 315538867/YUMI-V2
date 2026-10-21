package com.yumi.production.infrastructure;

import com.yumi.production.application.port.OtherTaskRepository;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 其他任务详情持久化（MySQL）。 */
@Repository
public class JdbcOtherTaskRepository implements OtherTaskRepository {

    private final JdbcClient jdbc;

    public JdbcOtherTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(OtherTaskRecord record) {
        jdbc.sql("""
                        INSERT INTO production_other_task_details (
                            task_business_id, work_content, related_product_business_id,
                            product_snapshot_ref, planned_quantity, frozen_unit_labor_amount,
                            result_note, row_version)
                        VALUES (:taskId, :workContent, :relatedProduct, :productSnapshotRef,
                                :plannedQuantity, :unitLabor, :resultNote, 1)
                        """)
                .param("taskId", record.taskBusinessId())
                .param("workContent", record.workContent())
                .param("relatedProduct", record.relatedProductBusinessId())
                .param("productSnapshotRef", record.productSnapshotRef())
                .param("plannedQuantity", record.plannedQuantity())
                .param("unitLabor", record.frozenUnitLaborAmount() == null
                        ? null : record.frozenUnitLaborAmount().value())
                .param("resultNote", record.resultNote())
                .update();
    }

    @Override
    public void update(OtherTaskRecord record) {
        jdbc.sql("""
                        UPDATE production_other_task_details
                        SET work_content = :workContent,
                            related_product_business_id = :relatedProduct,
                            product_snapshot_ref = :productSnapshotRef,
                            planned_quantity = :plannedQuantity,
                            frozen_unit_labor_amount = :unitLabor,
                            result_note = :resultNote, row_version = row_version + 1
                        WHERE task_business_id = :taskId
                        """)
                .param("taskId", record.taskBusinessId())
                .param("workContent", record.workContent())
                .param("relatedProduct", record.relatedProductBusinessId())
                .param("productSnapshotRef", record.productSnapshotRef())
                .param("plannedQuantity", record.plannedQuantity())
                .param("unitLabor", record.frozenUnitLaborAmount() == null
                        ? null : record.frozenUnitLaborAmount().value())
                .param("resultNote", record.resultNote())
                .update();
    }

    @Override
    public Optional<OtherTaskRecord> find(String taskBusinessId) {
        return jdbc.sql("""
                        SELECT task_business_id, work_content, related_product_business_id,
                               product_snapshot_ref, planned_quantity, frozen_unit_labor_amount,
                               result_note, row_version
                        FROM production_other_task_details WHERE task_business_id = :taskId
                        """)
                .param("taskId", taskBusinessId)
                .query((resultSet, rowNumber) -> {
                    java.math.BigDecimal unitLabor = resultSet.getBigDecimal("frozen_unit_labor_amount");
                    Integer plannedQuantity = resultSet.getObject("planned_quantity") == null
                            ? null : resultSet.getInt("planned_quantity");
                    return new OtherTaskRecord(
                            resultSet.getString("task_business_id"),
                            resultSet.getString("work_content"),
                            resultSet.getString("related_product_business_id"),
                            resultSet.getString("product_snapshot_ref"),
                            plannedQuantity,
                            unitLabor == null ? null : com.yumi.platform.api.value.CostAmount.of(
                                    unitLabor, com.yumi.platform.api.value.CurrencyCode.CNY),
                            resultSet.getString("result_note"),
                            resultSet.getLong("row_version"));
                })
                .optional();
    }
}
