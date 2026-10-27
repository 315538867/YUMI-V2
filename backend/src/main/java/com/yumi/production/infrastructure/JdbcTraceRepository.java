package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.api.DemandType;
import com.yumi.production.application.port.DemandRecord;
import com.yumi.production.application.port.TraceRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 追溯与公开投影读取（MySQL，只读）。 */
@Repository
public class JdbcTraceRepository implements TraceRepository {

    private final JdbcClient jdbc;

    public JdbcTraceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findOrderBusinessIdOfDemand(String demandBusinessId) {
        return jdbc.sql("""
                        SELECT order_business_id FROM production_demands WHERE demand_business_id = :id
                        """)
                .param("id", demandBusinessId)
                .query(String.class)
                .optional();
    }

    @Override
    public List<DemandRecord> findDemandsOfOrderItem(String orderItemBusinessId) {
        return jdbc.sql("""
                        SELECT id, demand_business_id, demand_type, source_module, source_type,
                               source_business_id, order_business_id, order_item_business_id,
                               product_business_id, product_snapshot_ref, scheme_business_id,
                               scheme_snapshot_ref, target_process, basis_returned_source_business_id,
                               production_basis_json, production_basis_fingerprint, required_quantity,
                               status, content_version, row_version, created_at, updated_at
                        FROM production_demands WHERE order_item_business_id = :id
                        ORDER BY demand_business_id
                        """)
                .param("id", orderItemBusinessId)
                .query((resultSet, rowNumber) -> new DemandRecord(
                        resultSet.getLong("id"),
                        resultSet.getString("demand_business_id"),
                        DemandType.valueOf(resultSet.getString("demand_type")),
                        resultSet.getString("source_module"),
                        resultSet.getString("source_type"),
                        resultSet.getString("source_business_id"),
                        resultSet.getString("order_business_id"),
                        resultSet.getString("order_item_business_id"),
                        resultSet.getString("product_business_id"),
                        resultSet.getString("product_snapshot_ref"),
                        resultSet.getString("scheme_business_id"),
                        resultSet.getString("scheme_snapshot_ref"),
                        com.yumi.production.api.TargetProcess.valueOf(resultSet.getString("target_process")),
                        resultSet.getString("basis_returned_source_business_id"),
                        resultSet.getString("production_basis_json"),
                        resultSet.getString("production_basis_fingerprint"),
                        resultSet.getInt("required_quantity"),
                        com.yumi.production.api.DemandStatus.valueOf(resultSet.getString("status")),
                        resultSet.getLong("content_version"),
                        resultSet.getLong("row_version"),
                        JdbcTimes.toInstant(resultSet.getObject("created_at", java.time.LocalDateTime.class)),
                        JdbcTimes.toInstant(resultSet.getObject("updated_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public List<String> listOrderItemsWithDemands(int offset, int limit) {
        return jdbc.sql("""
                        SELECT order_item_business_id
                        FROM production_demands
                        WHERE order_item_business_id IS NOT NULL AND status <> 'CANCELLED'
                        GROUP BY order_item_business_id
                        ORDER BY MAX(updated_at) DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("limit", limit)
                .param("offset", offset)
                .query(String.class)
                .list();
    }

    @Override
    public Optional<TraceHeader> findTraceHeader(String orderItemBusinessId) {
        return jdbc.sql("""
                        SELECT o.order_no,
                               COALESCE(GROUP_CONCAT(DISTINCT pp.name ORDER BY pp.name SEPARATOR '、'),
                                        '') AS product_names
                        FROM orders_items oi
                        JOIN orders_orders o ON o.order_business_id = oi.order_business_id
                        LEFT JOIN product_products pp
                               ON pp.product_business_id = oi.product_business_id
                        WHERE oi.order_item_business_id = :id
                        GROUP BY o.order_no
                        """)
                .param("id", orderItemBusinessId)
                .query((resultSet, rowNumber) -> new TraceHeader(
                        resultSet.getString("order_no"), resultSet.getString("product_names")))
                .optional();
    }

    @Override
    public List<OverviewDemandRow> overviewDemands() {
        return jdbc.sql("""
                        SELECT d.demand_business_id, d.order_business_id, d.target_process,
                               p.required_quantity, p.fulfilled_quantity,
                               COALESCE(agg.scheduled_making, 0) AS scheduled_making,
                               COALESCE(agg.scheduled_bagging, 0) AS scheduled_bagging,
                               COALESCE(agg.scheduled_seam, 0) AS scheduled_seam,
                               COALESCE(agg.processed_making, 0) AS processed_making,
                               COALESCE(agg.processed_bagging, 0) AS processed_bagging,
                               COALESCE(agg.processed_seam, 0) AS processed_seam,
                               COALESCE(agg.completed_making, 0) AS completed_making,
                               COALESCE(agg.completed_bagging, 0) AS completed_bagging,
                               COALESCE(agg.completed_seam, 0) AS completed_seam
                        FROM production_demands d
                        JOIN production_demand_quantity_projections p
                               ON p.demand_business_id = d.demand_business_id
                        LEFT JOIN (
                            SELECT ti.demand_business_id,
                                   SUM(CASE WHEN t.process_code = 'MAKING'
                                            THEN ti.planned_quantity ELSE 0 END) AS scheduled_making,
                                   SUM(CASE WHEN t.process_code = 'BAGGING'
                                            THEN ti.planned_quantity ELSE 0 END) AS scheduled_bagging,
                                   SUM(CASE WHEN t.process_code = 'SEAM_CUTTING'
                                            THEN ti.planned_quantity ELSE 0 END) AS scheduled_seam,
                                   SUM(CASE WHEN t.process_code = 'MAKING'
                                            THEN COALESCE(ti.completed_quantity, 0) ELSE 0 END)
                                       AS processed_making,
                                   SUM(CASE WHEN t.process_code = 'BAGGING'
                                            THEN COALESCE(ti.completed_quantity, 0) ELSE 0 END)
                                       AS processed_bagging,
                                   SUM(CASE WHEN t.process_code = 'SEAM_CUTTING'
                                            THEN COALESCE(ti.completed_quantity, 0) ELSE 0 END)
                                       AS processed_seam,
                                   SUM(CASE WHEN t.process_code = 'MAKING'
                                            THEN COALESCE(ti.qualified_quantity, 0) ELSE 0 END)
                                       AS completed_making,
                                   SUM(CASE WHEN t.process_code = 'BAGGING'
                                            THEN COALESCE(ti.qualified_quantity, 0) ELSE 0 END)
                                       AS completed_bagging,
                                   SUM(CASE WHEN t.process_code = 'SEAM_CUTTING'
                                            THEN COALESCE(ti.qualified_quantity, 0) ELSE 0 END)
                                       AS completed_seam
                            FROM production_tasks t
                            JOIN production_task_items ti ON ti.task_business_id = t.task_business_id
                            WHERE t.status <> 'CANCELLED'
                            GROUP BY ti.demand_business_id
                        ) agg ON agg.demand_business_id = d.demand_business_id
                        WHERE d.status <> 'CANCELLED'
                        ORDER BY d.order_business_id, d.demand_business_id
                        """)
                .query((resultSet, rowNumber) -> new OverviewDemandRow(
                        resultSet.getString("demand_business_id"),
                        resultSet.getString("order_business_id"),
                        com.yumi.production.api.TargetProcess.valueOf(
                                resultSet.getString("target_process")),
                        resultSet.getInt("required_quantity"),
                        resultSet.getInt("fulfilled_quantity"),
                        resultSet.getInt("scheduled_making"),
                        resultSet.getInt("scheduled_bagging"),
                        resultSet.getInt("scheduled_seam"),
                        resultSet.getInt("processed_making"),
                        resultSet.getInt("processed_bagging"),
                        resultSet.getInt("processed_seam"),
                        resultSet.getInt("completed_making"),
                        resultSet.getInt("completed_bagging"),
                        resultSet.getInt("completed_seam")))
                .list();
    }

    @Override
    public List<TaskRow> findTasksOfSource(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT t.task_business_id, t.task_no, t.task_type, t.work_date, t.process_code,
                               t.subject_type, t.subject_business_id, t.planned_work_quantity,
                               t.task_standard_seconds, t.status, i.production_source_business_id
                        FROM production_tasks t
                        JOIN production_task_items i ON i.task_business_id = t.task_business_id
                        WHERE i.production_source_business_id = :id
                        GROUP BY t.task_business_id, t.task_no, t.task_type, t.work_date, t.process_code,
                                 t.subject_type, t.subject_business_id, t.planned_work_quantity,
                                 t.task_standard_seconds, t.status, i.production_source_business_id
                        ORDER BY t.work_date, t.task_no
                        """)
                .param("id", productionSourceBusinessId)
                .query((resultSet, rowNumber) -> new TaskRow(
                        resultSet.getString("task_business_id"),
                        resultSet.getString("task_no"),
                        resultSet.getString("task_type"),
                        resultSet.getObject("work_date", LocalDate.class),
                        resultSet.getString("process_code"),
                        resultSet.getString("subject_type"),
                        resultSet.getString("subject_business_id"),
                        resultSet.getInt("planned_work_quantity"),
                        resultSet.getLong("task_standard_seconds"),
                        resultSet.getString("status"),
                        resultSet.getString("production_source_business_id")))
                .list();
    }

    @Override
    public List<ItemRow> findItemsOfSource(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT task_item_business_id, task_business_id, production_source_business_id,
                               demand_type, planned_quantity, completed_quantity, unfinished_quantity,
                               qualified_quantity, rework_quantity, scrapped_quantity, verification_status
                        FROM production_task_items
                        WHERE production_source_business_id = :id
                        ORDER BY task_business_id, item_order
                        """)
                .param("id", productionSourceBusinessId)
                .query((resultSet, rowNumber) -> new ItemRow(
                        resultSet.getString("task_item_business_id"),
                        resultSet.getString("task_business_id"),
                        resultSet.getString("production_source_business_id"),
                        DemandType.valueOf(resultSet.getString("demand_type")),
                        resultSet.getInt("planned_quantity"),
                        intOrNull(resultSet, "completed_quantity"),
                        intOrNull(resultSet, "unfinished_quantity"),
                        intOrNull(resultSet, "qualified_quantity"),
                        intOrNull(resultSet, "rework_quantity"),
                        intOrNull(resultSet, "scrapped_quantity"),
                        resultSet.getString("verification_status")))
                .list();
    }

    @Override
    public List<FactRow> findFactsOfSource(String productionSourceBusinessId) {
        String sql = """
                SELECT 'EXECUTION' AS fact_kind, fact_business_id, production_source_business_id,
                       physical_root_source_business_id, task_business_id, task_work_date, occurred_at,
                       quantity, process_code AS detail
                FROM production_execution_facts
                WHERE production_source_business_id = :id AND is_valid = 1
                UNION ALL
                SELECT 'DEFECT', d.fact_business_id, i.production_source_business_id,
                       i.physical_root_source_business_id, i.task_business_id, tk.work_date,
                       d.occurred_at, d.quantity, d.defect_reason_name
                FROM production_defect_facts d
                JOIN production_task_items i ON i.task_item_business_id = d.task_item_business_id
                JOIN production_tasks tk ON tk.task_business_id = i.task_business_id
                WHERE i.production_source_business_id = :id AND d.is_valid = 1
                UNION ALL
                SELECT 'TERMINAL_EXIT', e.terminal_exit_business_id, e.production_source_business_id,
                       e.physical_root_source_business_id, e.task_item_business_id,
                       (SELECT tk.work_date FROM production_task_items i
                          JOIN production_tasks tk ON tk.task_business_id = i.task_business_id
                         WHERE i.task_item_business_id = e.task_item_business_id),
                       e.occurred_at, e.quantity, e.physical_destination
                FROM production_terminal_exits e
                WHERE e.production_source_business_id = :id AND e.is_valid = 1
                UNION ALL
                SELECT 'CONSUMPTION', c.consumption_fact_business_id, c.production_source_business_id,
                       c.physical_root_source_business_id, NULL, NULL, c.occurred_at, c.quantity,
                       c.effect_type
                FROM production_consumption_facts c
                WHERE c.production_source_business_id = :id AND c.is_valid = 1
                UNION ALL
                SELECT 'OFFLINE_TRANSFER', f.transfer_fact_business_id, f.production_source_business_id,
                       f.physical_root_source_business_id, NULL, NULL, f.occurred_at, f.quantity,
                       f.reason_code
                FROM production_offline_inventory_transfers f
                WHERE f.production_source_business_id = :id AND f.is_valid = 1
                ORDER BY occurred_at, fact_kind, fact_business_id
                """;
        return jdbc.sql(sql)
                .param("id", productionSourceBusinessId)
                .query((resultSet, rowNumber) -> new FactRow(
                        resultSet.getString("fact_kind"),
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("production_source_business_id"),
                        resultSet.getString("physical_root_source_business_id"),
                        resultSet.getString("task_business_id"),
                        resultSet.getObject("task_work_date", LocalDate.class),
                        JdbcTimes.toInstant(resultSet.getObject("occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getInt("quantity"),
                        resultSet.getString("detail")))
                .list();
    }

    @Override
    public int pendingVerificationQuantityOfSource(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(planned_quantity), 0) FROM production_task_items
                        WHERE production_source_business_id = :id AND verification_status = 'PENDING'
                        """)
                .param("id", productionSourceBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public int delayedVerificationCountOfSource(String productionSourceBusinessId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM production_task_item_verifications v
                        JOIN production_task_items i ON i.task_item_business_id = v.task_item_business_id
                        WHERE i.production_source_business_id = :id AND v.is_valid = 1
                          AND DATE(v.verified_at) > v.task_work_date
                        """)
                .param("id", productionSourceBusinessId)
                .query(Integer.class)
                .single();
    }

    @Override
    public List<ProjectionRow> listProjections(
            Instant updatedAfter, String afterDemandBusinessId, int limit) {
        return jdbc.sql(PROJECTION_SELECT + """
                         WHERE (p.updated_at > :updatedAfter
                                OR (p.updated_at = :updatedAfter AND p.demand_business_id > :afterId))
                         ORDER BY p.updated_at, p.demand_business_id
                         LIMIT :limit
                        """)
                .param("updatedAfter", JdbcTimes.toUtc(updatedAfter == null ? Instant.EPOCH : updatedAfter))
                .param("afterId", afterDemandBusinessId == null ? "" : afterDemandBusinessId)
                .param("limit", limit)
                .query(JdbcTraceRepository::mapProjection)
                .list();
    }

    @Override
    public List<ProjectionRow> projectionsOfProduct(String productBusinessId, int limit) {
        return jdbc.sql(PROJECTION_SELECT + """
                         WHERE d.product_business_id = :productId
                         ORDER BY p.updated_at DESC, p.demand_business_id
                         LIMIT :limit
                        """)
                .param("productId", productBusinessId)
                .param("limit", limit)
                .query(JdbcTraceRepository::mapProjection)
                .list();
    }

    @Override
    public Optional<ProjectionRow> findProjection(String demandBusinessId) {
        return jdbc.sql(PROJECTION_SELECT + " WHERE p.demand_business_id = :id")
                .param("id", demandBusinessId)
                .query(JdbcTraceRepository::mapProjection)
                .optional();
    }

    private static final String PROJECTION_SELECT = """
            SELECT p.demand_business_id, s.production_source_business_id, d.order_item_business_id,
                   d.product_business_id, d.demand_type, p.required_quantity, p.pending_work_quantity,
                   p.in_process_quantity, p.target_ready_quantity, p.fulfilled_quantity,
                   p.fulfillment_gap_quantity, ph.cumulative_making_created_quantity,
                   ph.in_system_quantity, ph.consumed_quantity, ph.destroyed_quantity,
                   ph.offline_inventory_quantity, p.updated_at
            FROM production_demand_quantity_projections p
            JOIN production_demands d ON d.demand_business_id = p.demand_business_id
            LEFT JOIN production_sources s ON s.demand_business_id = p.demand_business_id
            LEFT JOIN production_physical_quantity_projections ph
                   ON ph.physical_root_source_business_id = s.physical_root_source_business_id
            """;

    private static ProjectionRow mapProjection(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ProjectionRow(
                resultSet.getString("demand_business_id"),
                resultSet.getString("production_source_business_id"),
                resultSet.getString("order_item_business_id"),
                resultSet.getString("product_business_id"),
                DemandType.valueOf(resultSet.getString("demand_type")),
                resultSet.getInt("required_quantity"),
                resultSet.getInt("pending_work_quantity"),
                resultSet.getInt("in_process_quantity"),
                resultSet.getInt("target_ready_quantity"),
                resultSet.getInt("fulfilled_quantity"),
                resultSet.getInt("fulfillment_gap_quantity"),
                resultSet.getInt("cumulative_making_created_quantity"),
                resultSet.getInt("in_system_quantity"),
                resultSet.getInt("consumed_quantity"),
                resultSet.getInt("destroyed_quantity"),
                resultSet.getInt("offline_inventory_quantity"),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", java.time.LocalDateTime.class)));
    }

    private static Integer intOrNull(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        return resultSet.getObject(column) == null ? null : resultSet.getInt(column);
    }
}
