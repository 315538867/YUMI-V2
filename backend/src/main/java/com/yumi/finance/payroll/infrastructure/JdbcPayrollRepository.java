package com.yumi.finance.payroll.infrastructure;

import com.yumi.finance.payroll.application.PayrollRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 工资期间、结果与参考运行持久化（MySQL）。 */
@Repository
public class JdbcPayrollRepository implements PayrollRepository {

    private final JdbcClient jdbc;

    public JdbcPayrollRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean periodsOverlap(LocalDate startDate, LocalDate endDate) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM finance_payroll_periods
                        WHERE start_date <= :endDate AND end_date >= :startDate
                        """)
                .param("startDate", java.sql.Date.valueOf(startDate))
                .param("endDate", java.sql.Date.valueOf(endDate))
                .query(Long.class).single() > 0;
    }

    @Override
    public Optional<PeriodRecord> findPeriodByNo(String periodNo) {
        return jdbc.sql("""
                        SELECT period_business_id, period_no, start_date, end_date, status,
                               content_version, row_version, created_at
                        FROM finance_payroll_periods WHERE period_no = :no
                        """)
                .param("no", periodNo)
                .query(JdbcPayrollRepository::mapPeriod)
                .optional();
    }

    @Override
    public Optional<PeriodRecord> findPeriod(String periodBusinessId) {
        return jdbc.sql("""
                        SELECT period_business_id, period_no, start_date, end_date, status,
                               content_version, row_version, created_at
                        FROM finance_payroll_periods WHERE period_business_id = :id
                        """)
                .param("id", periodBusinessId)
                .query(JdbcPayrollRepository::mapPeriod)
                .optional();
    }

    @Override
    public void insertPeriod(PeriodRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_periods (
                            period_business_id, period_no, start_date, end_date, status,
                            content_version, row_version, created_at, created_by)
                        VALUES (:id, :no, :startDate, :endDate, 'OPEN', 1, 1, :createdAt, :createdBy)
                        """)
                .param("id", record.periodBusinessId())
                .param("no", record.periodNo())
                .param("startDate", java.sql.Date.valueOf(record.startDate()))
                .param("endDate", java.sql.Date.valueOf(record.endDate()))
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .param("createdBy", "SYSTEM")
                .update();
    }

    @Override
    public List<PeriodRecord> listPeriods() {
        return jdbc.sql("""
                        SELECT period_business_id, period_no, start_date, end_date, status,
                               content_version, row_version, created_at
                        FROM finance_payroll_periods ORDER BY start_date
                        """)
                .query(JdbcPayrollRepository::mapPeriod)
                .list();
    }

    @Override
    public Optional<ResultRecord> findResult(String periodBusinessId, String employeeBusinessId) {
        return jdbc.sql("""
                        SELECT result_business_id, period_business_id, employee_business_id, status,
                               reference_run_business_id, reference_fingerprint, confirmed_amount,
                               payable_amount, row_version
                        FROM finance_payroll_results
                        WHERE period_business_id = :periodId AND employee_business_id = :employeeId
                        """)
                .param("periodId", periodBusinessId)
                .param("employeeId", employeeBusinessId)
                .query(JdbcPayrollRepository::mapResult)
                .optional();
    }

    @Override
    public Optional<ResultRecord> lockResultById(String resultBusinessId) {
        return jdbc.sql("""
                        SELECT result_business_id, period_business_id, employee_business_id, status,
                               reference_run_business_id, reference_fingerprint, confirmed_amount,
                               payable_amount, row_version
                        FROM finance_payroll_results WHERE result_business_id = :id FOR UPDATE
                        """)
                .param("id", resultBusinessId)
                .query(JdbcPayrollRepository::mapResult)
                .optional();
    }

    @Override
    public Optional<ResultRecord> findResultById(String resultBusinessId) {
        return jdbc.sql("""
                        SELECT result_business_id, period_business_id, employee_business_id, status,
                               reference_run_business_id, reference_fingerprint, confirmed_amount,
                               payable_amount, row_version
                        FROM finance_payroll_results WHERE result_business_id = :id
                        """)
                .param("id", resultBusinessId)
                .query(JdbcPayrollRepository::mapResult)
                .optional();
    }

    @Override
    public ResultRecord ensureResult(String periodBusinessId, String employeeBusinessId) {
        jdbc.sql("""
                        INSERT IGNORE INTO finance_payroll_results (
                            result_business_id, period_business_id, employee_business_id, status,
                            row_version, created_at, updated_at)
                        VALUES (:id, :periodId, :employeeId, 'PENDING_REFERENCE', 1,
                                UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """)
                .param("id", "PYR-" + UUID.randomUUID())
                .param("periodId", periodBusinessId)
                .param("employeeId", employeeBusinessId)
                .update();
        return findResult(periodBusinessId, employeeBusinessId).orElseThrow();
    }

    @Override
    public void updateResultReference(
            String resultBusinessId, String runBusinessId, String fingerprint, String status) {
        jdbc.sql("""
                        UPDATE finance_payroll_results
                        SET reference_run_business_id = :runId, reference_fingerprint = :fingerprint,
                            status = :status, updated_at = UTC_TIMESTAMP(6),
                            row_version = row_version + 1
                        WHERE result_business_id = :id AND status <> 'CONFIRMED'
                        """)
                .param("runId", runBusinessId)
                .param("fingerprint", fingerprint)
                .param("status", status)
                .param("id", resultBusinessId)
                .update();
    }

    @Override
    public List<ResultRecord> listResults(String periodBusinessId) {
        return jdbc.sql("""
                        SELECT result_business_id, period_business_id, employee_business_id, status,
                               reference_run_business_id, reference_fingerprint, confirmed_amount,
                               payable_amount, row_version
                        FROM finance_payroll_results
                        WHERE period_business_id = :id ORDER BY employee_business_id
                        """)
                .param("id", periodBusinessId)
                .query(JdbcPayrollRepository::mapResult)
                .list();
    }

    @Override
    public void insertReferenceRun(ReferenceRunRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_reference_runs (
                            run_business_id, period_business_id, employee_business_id, run_type,
                            fingerprint, source_versions_json, referenced_run_business_id,
                            generated_at, operator_id)
                        VALUES (:id, :periodId, :employeeId, :runType, :fingerprint, :versions,
                                :referencedRunId, :generatedAt, :operatorId)
                        """)
                .param("id", record.runBusinessId())
                .param("periodId", record.periodBusinessId())
                .param("employeeId", record.employeeBusinessId())
                .param("runType", record.runType())
                .param("fingerprint", record.fingerprint())
                .param("versions", record.sourceVersionsJson())
                .param("referencedRunId", record.referencedRunBusinessId())
                .param("generatedAt", JdbcTimes.toUtc(record.generatedAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertReferenceLine(ReferenceLineRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_reference_lines (
                            line_business_id, run_business_id, line_type, source_business_id,
                            component_code, quantity, unit_amount, reference_amount, occurred,
                            estimated, material_source, incomplete, note, occurred_at)
                        VALUES (:id, :runId, :type, :sourceId, :componentCode, :quantity, :unitAmount,
                                :amount, :occurred, :estimated, :materialSource, :incomplete, :note,
                                :occurredAt)
                        """)
                .param("id", record.lineBusinessId())
                .param("runId", record.runBusinessId())
                .param("type", record.lineType())
                .param("sourceId", record.sourceBusinessId())
                .param("componentCode", record.componentCode())
                .param("quantity", record.quantity())
                .param("unitAmount", record.unitAmount())
                .param("amount", record.referenceAmount())
                .param("occurred", record.occurred())
                .param("estimated", record.estimated())
                .param("materialSource", record.materialSource())
                .param("incomplete", record.incomplete())
                .param("note", record.note())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .update();
    }

    @Override
    public List<ReferenceLineRecord> listReferenceLines(String runBusinessId) {
        return jdbc.sql("""
                        SELECT line_business_id, run_business_id, line_type, source_business_id,
                               component_code, quantity, unit_amount, reference_amount, occurred,
                               estimated, material_source, incomplete, note, occurred_at
                        FROM finance_payroll_reference_lines
                        WHERE run_business_id = :id ORDER BY line_type, source_business_id
                        """)
                .param("id", runBusinessId)
                .query((resultSet, rowNumber) -> new ReferenceLineRecord(
                        resultSet.getString("line_business_id"),
                        resultSet.getString("run_business_id"),
                        resultSet.getString("line_type"),
                        resultSet.getString("source_business_id"),
                        resultSet.getString("component_code"),
                        (Integer) resultSet.getObject("quantity"),
                        resultSet.getBigDecimal("unit_amount"),
                        resultSet.getBigDecimal("reference_amount"),
                        resultSet.getBoolean("occurred"),
                        resultSet.getBoolean("estimated"),
                        resultSet.getString("material_source"),
                        resultSet.getBoolean("incomplete"),
                        resultSet.getString("note"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public Long monthGuardVersion(String employeeBusinessId, LocalDate monthStart) {
        return jdbc.sql("""
                        SELECT source_version FROM finance_payroll_month_guards
                        WHERE employee_business_id = :id AND month_start = :month
                        """)
                .param("id", employeeBusinessId)
                .param("month", java.sql.Date.valueOf(monthStart))
                .query(Long.class).optional().orElse(null);
    }

    @Override
    public List<String> distinctLaborEmployees(LocalDate startDate, LocalDate endDate) {
        return jdbc.sql("""
                        SELECT DISTINCT employee_id FROM (
                            SELECT subject_business_id AS employee_id
                            FROM finance_payroll_labor_entries
                            WHERE business_date BETWEEN :startDate AND :endDate
                              AND subject_type = 'EMPLOYEE'
                            UNION
                            SELECT j.participant AS employee_id
                            FROM finance_payroll_labor_entries l
                            JOIN JSON_TABLE(l.participants_json, '$[*]' COLUMNS (participant VARCHAR(64) PATH '$')) j
                            WHERE l.business_date BETWEEN :startDate AND :endDate
                              AND l.subject_type = 'TEAM'
                        ) employees ORDER BY employee_id
                        """)
                .param("startDate", java.sql.Date.valueOf(startDate))
                .param("endDate", java.sql.Date.valueOf(endDate))
                .query(String.class)
                .list();
    }

    private static PeriodRecord mapPeriod(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new PeriodRecord(
                resultSet.getString("period_business_id"),
                resultSet.getString("period_no"),
                resultSet.getObject("start_date", LocalDate.class),
                resultSet.getObject("end_date", LocalDate.class),
                resultSet.getString("status"),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", java.time.LocalDateTime.class)));
    }

    private static ResultRecord mapResult(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new ResultRecord(
                resultSet.getString("result_business_id"),
                resultSet.getString("period_business_id"),
                resultSet.getString("employee_business_id"),
                resultSet.getString("status"),
                resultSet.getString("reference_run_business_id"),
                resultSet.getString("reference_fingerprint"),
                resultSet.getBigDecimal("confirmed_amount"),
                resultSet.getBigDecimal("payable_amount"),
                resultSet.getLong("row_version"));
    }

    @Override
    public void confirmResult(
            String resultBusinessId, java.math.BigDecimal confirmedAmount, long expectedRowVersion) {
        int updated = jdbc.sql("""
                        UPDATE finance_payroll_results
                        SET status = 'CONFIRMED', confirmed_amount = :amount,
                            payable_amount = :amount,
                            updated_at = UTC_TIMESTAMP(6), row_version = row_version + 1
                        WHERE result_business_id = :id AND row_version = :version
                          AND status = 'REFERENCE_READY'
                        """)
                .param("amount", confirmedAmount)
                .param("id", resultBusinessId)
                .param("version", expectedRowVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("工资结果版本冲突或状态不允许核定：" + resultBusinessId);
        }
    }

    @Override
    public void insertConfirmation(ConfirmationRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_confirmations (
                            confirmation_business_id, result_business_id, confirmed_amount,
                            reference_run_business_id, reference_fingerprint, note, occurred_at,
                            operator_id, is_valid)
                        VALUES (:id, :resultId, :amount, :runId, :fingerprint, :note, :occurredAt,
                                :operatorId, 1)
                        """)
                .param("id", record.confirmationBusinessId())
                .param("resultId", record.resultBusinessId())
                .param("amount", record.confirmedAmount())
                .param("runId", record.referenceRunBusinessId())
                .param("fingerprint", record.referenceFingerprint())
                .param("note", record.note())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void insertProcessingItem(ProcessingItemRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_processing_items (
                            item_business_id, result_business_id, confirmation_business_id,
                            reference_line_business_id, manual_amount, note, created_at)
                        VALUES (:id, :resultId, :confirmationId, :lineId, :amount, :note, :createdAt)
                        """)
                .param("id", record.itemBusinessId())
                .param("resultId", record.resultBusinessId())
                .param("confirmationId", record.confirmationBusinessId())
                .param("lineId", record.referenceLineBusinessId())
                .param("amount", record.manualAmount())
                .param("note", record.note())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public void insertDirectLaborCost(DirectLaborCostRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_direct_labor_costs (
                            cost_source_business_id, result_business_id, amount, business_date,
                            created_at)
                        VALUES (:id, :resultId, :amount, :businessDate, :createdAt)
                        """)
                .param("id", record.costSourceBusinessId())
                .param("resultId", record.resultBusinessId())
                .param("amount", record.amount())
                .param("businessDate", java.sql.Date.valueOf(record.businessDate()))
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public Optional<ConfirmationRecord> findConfirmationByResult(String resultBusinessId) {
        return jdbc.sql("""
                        SELECT confirmation_business_id, result_business_id, confirmed_amount,
                               reference_run_business_id, reference_fingerprint, note, occurred_at,
                               operator_id
                        FROM finance_payroll_confirmations
                        WHERE result_business_id = :id AND is_valid = 1
                        ORDER BY occurred_at DESC LIMIT 1
                        """)
                .param("id", resultBusinessId)
                .query((resultSet, rowNumber) -> new ConfirmationRecord(
                        resultSet.getString("confirmation_business_id"),
                        resultSet.getString("result_business_id"),
                        resultSet.getBigDecimal("confirmed_amount"),
                        resultSet.getString("reference_run_business_id"),
                        resultSet.getString("reference_fingerprint"),
                        resultSet.getString("note"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public List<ProcessingItemRecord> listProcessingItems(String resultBusinessId) {
        return jdbc.sql("""
                        SELECT item_business_id, result_business_id, confirmation_business_id,
                               reference_line_business_id, manual_amount, note, created_at
                        FROM finance_payroll_processing_items
                        WHERE result_business_id = :id ORDER BY item_business_id
                        """)
                .param("id", resultBusinessId)
                .query((resultSet, rowNumber) -> new ProcessingItemRecord(
                        resultSet.getString("item_business_id"),
                        resultSet.getString("result_business_id"),
                        resultSet.getString("confirmation_business_id"),
                        resultSet.getString("reference_line_business_id"),
                        resultSet.getBigDecimal("manual_amount"),
                        resultSet.getString("note"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public Optional<ReferenceRunRecord> findRun(String runBusinessId) {
        return jdbc.sql("""
                        SELECT run_business_id, period_business_id, employee_business_id, run_type,
                               fingerprint, source_versions_json, referenced_run_business_id,
                               generated_at, operator_id
                        FROM finance_payroll_reference_runs WHERE run_business_id = :id
                        """)
                .param("id", runBusinessId)
                .query((resultSet, rowNumber) -> new ReferenceRunRecord(
                        resultSet.getString("run_business_id"),
                        resultSet.getString("period_business_id"),
                        resultSet.getString("employee_business_id"),
                        resultSet.getString("run_type"),
                        resultSet.getString("fingerprint"),
                        resultSet.getString("source_versions_json"),
                        resultSet.getString("referenced_run_business_id"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "generated_at", java.time.LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .optional();
    }

    @Override
    public void insertAdjustment(AdjustmentRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_adjustments (
                            adjustment_business_id, result_business_id, amount_delta,
                            reference_run_business_id, reason_text, occurred_at, operator_id)
                        VALUES (:id, :resultId, :delta, :runId, :reason, :occurredAt, :operatorId)
                        """)
                .param("id", record.adjustmentBusinessId())
                .param("resultId", record.resultBusinessId())
                .param("delta", record.amountDelta())
                .param("runId", record.referenceRunBusinessId())
                .param("reason", record.reasonText())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public void updatePayableAmount(
            String resultBusinessId, java.math.BigDecimal payableAmount, long expectedRowVersion) {
        int updated = jdbc.sql("""
                        UPDATE finance_payroll_results
                        SET payable_amount = :amount, updated_at = UTC_TIMESTAMP(6),
                            row_version = row_version + 1
                        WHERE result_business_id = :id AND row_version = :version
                          AND status = 'CONFIRMED'
                        """)
                .param("amount", payableAmount)
                .param("id", resultBusinessId)
                .param("version", expectedRowVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("工资结果版本冲突：" + resultBusinessId);
        }
    }

    @Override
    public void insertCostAdjustment(CostAdjustmentRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_direct_labor_cost_adjustments (
                            adjustment_business_id, result_business_id, amount_delta, created_at)
                        VALUES (:id, :resultId, :delta, :createdAt)
                        """)
                .param("id", record.adjustmentBusinessId())
                .param("resultId", record.resultBusinessId())
                .param("delta", record.amountDelta())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .update();
    }

    @Override
    public java.math.BigDecimal paidAmount(String resultBusinessId) {
        return jdbc.sql("""
                        SELECT COALESCE(paid_amount, 0) FROM finance_payroll_payment_projections
                        WHERE result_business_id = :id
                        """).param("id", resultBusinessId)
                .query(java.math.BigDecimal.class).optional()
                .orElse(java.math.BigDecimal.ZERO);
    }

    @Override
    public void insertPaymentLink(PaymentLinkRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_payment_links (
                            link_business_id, result_business_id, finance_cash_fact_business_id,
                            amount, reverses_link_business_id, reason_text, occurred_at, operator_id,
                            is_valid)
                        VALUES (:id, :resultId, :cashFactId, :amount, :reversesId, :reason,
                                :occurredAt, :operatorId, 1)
                        """)
                .param("id", record.linkBusinessId())
                .param("resultId", record.resultBusinessId())
                .param("cashFactId", record.financeCashFactBusinessId())
                .param("amount", record.amount())
                .param("reversesId", record.reversesLinkBusinessId())
                .param("reason", record.reasonText())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<PaymentLinkRecord> findPaymentLink(String linkBusinessId) {
        return jdbc.sql("""
                        SELECT link_business_id, result_business_id, finance_cash_fact_business_id,
                               amount, reverses_link_business_id, reason_text, occurred_at, operator_id
                        FROM finance_payroll_payment_links WHERE link_business_id = :id
                        """)
                .param("id", linkBusinessId)
                .query(JdbcPayrollRepository::mapPaymentLink)
                .optional();
    }

    @Override
    public Optional<PaymentLinkRecord> findReversalOf(String originalLinkId) {
        return jdbc.sql("""
                        SELECT link_business_id, result_business_id, finance_cash_fact_business_id,
                               amount, reverses_link_business_id, reason_text, occurred_at, operator_id
                        FROM finance_payroll_payment_links WHERE reverses_link_business_id = :id
                        """)
                .param("id", originalLinkId)
                .query(JdbcPayrollRepository::mapPaymentLink)
                .optional();
    }

    @Override
    public void upsertPaidAmount(String resultBusinessId, java.math.BigDecimal paidAmount) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_payment_projections (
                            result_business_id, paid_amount, updated_at, row_version)
                        VALUES (:id, :paid, UTC_TIMESTAMP(6), 1)
                        ON DUPLICATE KEY UPDATE paid_amount = VALUES(paid_amount),
                            updated_at = UTC_TIMESTAMP(6), row_version = row_version + 1
                        """)
                .param("id", resultBusinessId)
                .param("paid", paidAmount)
                .update();
    }

    @Override
    public List<PayrollFeedRow> findResultsChangedAfter(
            java.time.Instant updatedAfter, String afterResultId, int limit) {
        return jdbc.sql("""
                        SELECT r.result_business_id, r.employee_business_id, r.period_business_id,
                               period.start_date, r.status, r.confirmed_amount,
                               r.payable_amount, COALESCE(p.paid_amount, 0) AS paid_amount,
                               r.row_version, r.updated_at
                        FROM finance_payroll_results r
                        JOIN finance_payroll_periods period
                          ON period.period_business_id = r.period_business_id
                        LEFT JOIN finance_payroll_payment_projections p
                          ON p.result_business_id = r.result_business_id
                        WHERE r.updated_at > :after
                           OR (r.updated_at = :after AND r.result_business_id > :afterId)
                        ORDER BY r.updated_at, r.result_business_id
                        LIMIT :limit
                        """)
                .param("after", JdbcTimes.toUtc(updatedAfter))
                .param("afterId", afterResultId == null ? "" : afterResultId)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new PayrollFeedRow(
                        resultSet.getString("result_business_id"),
                        resultSet.getString("employee_business_id"),
                        resultSet.getString("period_business_id"),
                        resultSet.getObject("start_date", java.time.LocalDate.class),
                        resultSet.getString("status"),
                        resultSet.getBigDecimal("confirmed_amount"),
                        resultSet.getBigDecimal("payable_amount"),
                        resultSet.getBigDecimal("paid_amount"),
                        resultSet.getLong("row_version"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "updated_at", java.time.LocalDateTime.class))))
                .list();
    }

    private static PaymentLinkRecord mapPaymentLink(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new PaymentLinkRecord(
                resultSet.getString("link_business_id"),
                resultSet.getString("result_business_id"),
                resultSet.getString("finance_cash_fact_business_id"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("reverses_link_business_id"),
                resultSet.getString("reason_text"),
                JdbcTimes.toInstant(resultSet.getObject(
                        "occurred_at", java.time.LocalDateTime.class)),
                resultSet.getString("operator_id"));
    }

    @Override
    public List<DirectLaborCostRow> listDirectLaborCosts(
            java.time.LocalDate start, java.time.LocalDate end) {
        return jdbc.sql("""
                        SELECT c.cost_source_business_id, c.result_business_id,
                               r.employee_business_id, c.amount, c.business_date, c.created_at
                        FROM finance_direct_labor_costs c
                        JOIN finance_payroll_results r ON r.result_business_id = c.result_business_id
                        WHERE c.business_date BETWEEN :start AND :end
                        ORDER BY c.business_date, c.cost_source_business_id
                        """)
                .param("start", java.sql.Date.valueOf(start))
                .param("end", java.sql.Date.valueOf(end))
                .query((resultSet, rowNumber) -> new DirectLaborCostRow(
                        resultSet.getString("cost_source_business_id"),
                        resultSet.getString("result_business_id"),
                        resultSet.getString("employee_business_id"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getObject("business_date", LocalDate.class),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public List<CostAdjustmentRow> listCostAdjustments(
            java.time.LocalDate start, java.time.LocalDate end) {
        return jdbc.sql("""
                        SELECT a.adjustment_business_id, a.result_business_id,
                               r.employee_business_id, c.amount_delta, c.created_at
                        FROM finance_direct_labor_cost_adjustments c
                        JOIN finance_payroll_adjustments a
                          ON a.adjustment_business_id = c.adjustment_business_id
                        JOIN finance_payroll_results r ON r.result_business_id = a.result_business_id
                        WHERE DATE(c.created_at) BETWEEN :start AND :end
                        ORDER BY c.created_at, c.adjustment_business_id
                        """)
                .param("start", java.sql.Date.valueOf(start))
                .param("end", java.sql.Date.valueOf(end))
                .query((resultSet, rowNumber) -> new CostAdjustmentRow(
                        resultSet.getString("adjustment_business_id"),
                        resultSet.getString("result_business_id"),
                        resultSet.getString("employee_business_id"),
                        resultSet.getBigDecimal("amount_delta"),
                        resultSet.getObject("created_at", java.time.LocalDateTime.class).toLocalDate(),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "created_at", java.time.LocalDateTime.class))))
                .list();
    }

    @Override
    public void insertPaymentReturn(PaymentReturnRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_payment_returns (
                            return_link_business_id, result_business_id,
                            original_finance_cash_fact_id, return_finance_cash_fact_id, amount,
                            operation_type, corrects_return_link_business_id, is_valid, created_at)
                        VALUES (:id, :resultId, :originalCashId, :returnCashId, :amount,
                                :operation, :correctsId, 1, UTC_TIMESTAMP(6))
                        """)
                .param("id", record.returnLinkBusinessId())
                .param("resultId", record.resultBusinessId())
                .param("originalCashId", record.originalFinanceCashFactId())
                .param("returnCashId", record.returnFinanceCashFactId())
                .param("amount", record.amount())
                .param("operation", record.operationType())
                .param("correctsId", record.correctsReturnLinkBusinessId())
                .update();
    }

    @Override
    public java.util.Optional<PaymentLinkRecord> findPaymentLinkByCashFact(
            String financeCashFactBusinessId) {
        return jdbc.sql("""
                        SELECT link_business_id, result_business_id, finance_cash_fact_business_id,
                               amount, reverses_link_business_id, reason_text, occurred_at, operator_id
                        FROM finance_payroll_payment_links
                        WHERE finance_cash_fact_business_id = :id
                          AND reverses_link_business_id IS NULL AND is_valid = 1
                        """)
                .param("id", financeCashFactBusinessId)
                .query(JdbcPayrollRepository::mapPaymentLink)
                .optional();
    }

    @Override
    public java.util.Optional<PaymentReturnRecord> findPaymentReturnByCashFact(String returnCashFactId) {
        return jdbc.sql("""
                        SELECT return_link_business_id, result_business_id,
                               original_finance_cash_fact_id, return_finance_cash_fact_id, amount,
                               operation_type, corrects_return_link_business_id, is_valid
                        FROM finance_payroll_payment_returns
                        WHERE return_finance_cash_fact_id = :id
                        """)
                .param("id", returnCashFactId)
                .query(JdbcPayrollRepository::mapPaymentReturn)
                .optional();
    }

    @Override
    public java.util.Optional<PaymentReturnRecord> findReturnReversal(String originalReturnLinkId) {
        return jdbc.sql("""
                        SELECT return_link_business_id, result_business_id,
                               original_finance_cash_fact_id, return_finance_cash_fact_id, amount,
                               operation_type, corrects_return_link_business_id, is_valid
                        FROM finance_payroll_payment_returns
                        WHERE corrects_return_link_business_id = :id AND is_valid = 1
                        """)
                .param("id", originalReturnLinkId)
                .query(JdbcPayrollRepository::mapPaymentReturn)
                .optional();
    }

    private static PaymentReturnRecord mapPaymentReturn(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new PaymentReturnRecord(
                resultSet.getString("return_link_business_id"),
                resultSet.getString("result_business_id"),
                resultSet.getString("original_finance_cash_fact_id"),
                resultSet.getString("return_finance_cash_fact_id"),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("operation_type"),
                resultSet.getString("corrects_return_link_business_id"),
                resultSet.getBoolean("is_valid"));
    }
}
