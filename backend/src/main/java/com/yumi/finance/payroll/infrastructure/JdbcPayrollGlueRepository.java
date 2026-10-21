package com.yumi.finance.payroll.infrastructure;

import com.yumi.finance.payroll.application.PayrollGlueRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 胶水策略与自然月参考持久化（MySQL）。 */
@Repository
public class JdbcPayrollGlueRepository implements PayrollGlueRepository {

    private final JdbcClient jdbc;

    public JdbcPayrollGlueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long lockGuardAndReadVersion() {
        jdbc.sql("""
                        INSERT IGNORE INTO finance_payroll_glue_policy_guard (guard_code, guard_version)
                        VALUES ('GLUE_POLICY', 0)
                        """).update();
        return jdbc.sql("""
                        SELECT guard_version FROM finance_payroll_glue_policy_guard
                        WHERE guard_code = 'GLUE_POLICY' FOR UPDATE
                        """).query(Long.class).single();
    }

    @Override
    public long readGuardVersion() {
        return jdbc.sql("""
                        SELECT guard_version FROM finance_payroll_glue_policy_guard
                        WHERE guard_code = 'GLUE_POLICY'
                        """).query(Long.class).optional().orElse(0L);
    }

    @Override
    public void updateGuardVersion(long version) {
        jdbc.sql("""
                        UPDATE finance_payroll_glue_policy_guard SET guard_version = :version
                        WHERE guard_code = 'GLUE_POLICY'
                        """).param("version", version).update();
    }

    @Override
    public long nextPolicyVersion(LocalDate effectiveMonth) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(version), 0) FROM finance_payroll_glue_policies
                        WHERE effective_month = :month
                        """).param("month", java.sql.Date.valueOf(effectiveMonth))
                .query(Long.class).single() + 1;
    }

    @Override
    public void insertPolicy(PolicyRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_glue_policies (
                            policy_business_id, effective_month, version, exempt_weight_grams,
                            reason_text, created_at, operator_id)
                        VALUES (:id, :month, :version, :weight, :reason, :createdAt, :operatorId)
                        """)
                .param("id", record.policyBusinessId())
                .param("month", java.sql.Date.valueOf(record.effectiveMonth()))
                .param("version", record.version())
                .param("weight", record.exemptWeightGrams())
                .param("reason", record.reasonText())
                .param("createdAt", JdbcTimes.toUtc(record.createdAt()))
                .param("operatorId", record.operatorId())
                .update();
    }

    @Override
    public Optional<PolicyRecord> findPolicyById(String policyId) {
        return jdbc.sql("""
                        SELECT policy_business_id, effective_month, version, exempt_weight_grams,
                               reason_text, created_at, operator_id
                        FROM finance_payroll_glue_policies WHERE policy_business_id = :id
                        """).param("id", policyId)
                .query(JdbcPayrollGlueRepository::mapPolicy)
                .optional();
    }

    @Override
    public Optional<PolicyRecord> findPolicyForMonth(LocalDate monthStart) {
        return jdbc.sql("""
                        SELECT policy_business_id, effective_month, version, exempt_weight_grams,
                               reason_text, created_at, operator_id
                        FROM finance_payroll_glue_policies
                        WHERE effective_month <= :month
                        ORDER BY effective_month DESC, version DESC
                        LIMIT 1
                        """).param("month", java.sql.Date.valueOf(monthStart))
                .query(JdbcPayrollGlueRepository::mapPolicy)
                .optional();
    }

    @Override
    public List<GlueSourceLine> listEffectiveEmployeeGlueLines(
            String employeeBusinessId, LocalDate monthStart, LocalDate monthEnd) {
        return jdbc.sql("""
                        SELECT r.responsibility_chain_business_id, r.disposition_fact_business_id,
                               r.quantity, r.glue_grams_per_piece, r.glue_unit_price_per_gram,
                               r.business_date, r.feed_sequence
                        FROM finance_payroll_responsibility_entries r
                        JOIN (
                            SELECT responsibility_chain_business_id, MAX(feed_sequence) AS max_seq
                            FROM finance_payroll_responsibility_entries
                            WHERE business_date BETWEEN :start AND :end
                            GROUP BY responsibility_chain_business_id
                        ) latest ON latest.responsibility_chain_business_id =
                                    r.responsibility_chain_business_id
                                AND latest.max_seq = r.feed_sequence
                        WHERE r.business_date BETWEEN :start AND :end
                          AND r.responsibility_type = 'EMPLOYEE'
                          AND r.responsible_subject_business_id = :employeeId
                          AND r.glue_eligible = 1
                          AND r.effect_type = 'APPLY'
                        ORDER BY r.business_date, r.responsibility_chain_business_id
                        """)
                .param("start", java.sql.Date.valueOf(monthStart))
                .param("end", java.sql.Date.valueOf(monthEnd))
                .param("employeeId", employeeBusinessId)
                .query((resultSet, rowNumber) -> new GlueSourceLine(
                        resultSet.getString("responsibility_chain_business_id"),
                        resultSet.getString("disposition_fact_business_id"),
                        resultSet.getInt("quantity"),
                        resultSet.getBigDecimal("glue_grams_per_piece"),
                        resultSet.getBigDecimal("glue_unit_price_per_gram"),
                        resultSet.getObject("business_date", LocalDate.class),
                        resultSet.getLong("feed_sequence")))
                .list();
    }

    @Override
    public long currentSourceVersion(
            String employeeBusinessId, LocalDate monthStart, LocalDate monthEnd) {
        return jdbc.sql("""
                        SELECT COALESCE(MAX(feed_sequence), 0)
                        FROM finance_payroll_responsibility_entries
                        WHERE business_date BETWEEN :start AND :end
                          AND responsible_subject_business_id = :employeeId
                        """)
                .param("start", java.sql.Date.valueOf(monthStart))
                .param("end", java.sql.Date.valueOf(monthEnd))
                .param("employeeId", employeeBusinessId)
                .query(Long.class).single();
    }

    @Override
    public void upsertReference(ReferenceRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_glue_month_references (
                            reference_business_id, employee_business_id, month_start,
                            policy_business_id, status, incomplete_reason, allocated_weight,
                            exempt_weight, reference_fingerprint, source_version, computed_at)
                        VALUES (:id, :employeeId, :month, :policyId, :status, :reason, :allocated,
                                :exempt, :fingerprint, :sourceVersion, :computedAt)
                        ON DUPLICATE KEY UPDATE
                            policy_business_id = VALUES(policy_business_id),
                            status = VALUES(status), incomplete_reason = VALUES(incomplete_reason),
                            allocated_weight = VALUES(allocated_weight),
                            exempt_weight = VALUES(exempt_weight),
                            reference_fingerprint = VALUES(reference_fingerprint),
                            source_version = VALUES(source_version),
                            computed_at = VALUES(computed_at)
                        """)
                .param("id", record.referenceBusinessId())
                .param("employeeId", record.employeeBusinessId())
                .param("month", java.sql.Date.valueOf(record.monthStart()))
                .param("policyId", record.policyBusinessId())
                .param("status", record.status())
                .param("reason", record.incompleteReason())
                .param("allocated", record.allocatedWeight())
                .param("exempt", record.exemptWeight())
                .param("fingerprint", record.fingerprint())
                .param("sourceVersion", record.sourceVersion())
                .param("computedAt", JdbcTimes.toUtc(record.computedAt()))
                .update();
    }

    @Override
    public void deleteLines(String referenceBusinessId) {
        jdbc.sql("DELETE FROM finance_payroll_glue_month_lines WHERE reference_business_id = :id")
                .param("id", referenceBusinessId).update();
    }

    @Override
    public Optional<ReferenceRecord> findReference(String employeeBusinessId, LocalDate monthStart) {
        return jdbc.sql("""
                        SELECT reference_business_id, employee_business_id, month_start,
                               policy_business_id, status, incomplete_reason, allocated_weight,
                               exempt_weight, reference_fingerprint, source_version, computed_at
                        FROM finance_payroll_glue_month_references
                        WHERE employee_business_id = :employeeId AND month_start = :month
                        """)
                .param("employeeId", employeeBusinessId)
                .param("month", java.sql.Date.valueOf(monthStart))
                .query((resultSet, rowNumber) -> new ReferenceRecord(
                        resultSet.getString("reference_business_id"),
                        resultSet.getString("employee_business_id"),
                        resultSet.getObject("month_start", LocalDate.class),
                        resultSet.getString("policy_business_id"),
                        resultSet.getString("status"),
                        resultSet.getString("incomplete_reason"),
                        resultSet.getBigDecimal("allocated_weight"),
                        resultSet.getBigDecimal("exempt_weight"),
                        resultSet.getString("reference_fingerprint"),
                        resultSet.getLong("source_version"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "computed_at", java.time.LocalDateTime.class))))
                .optional();
    }

    @Override
    public void insertLine(LineRecord record) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_glue_month_lines (
                            line_business_id, reference_business_id, responsibility_chain_business_id,
                            cost_line_key, business_date, allocated_weight, exempt_weight,
                            chargeable_weight, glue_unit_price, pre_amount, post_amount, occurred_at)
                        VALUES (:id, :referenceId, :chainId, :costLineKey, :businessDate,
                                :allocated, :exempt, :chargeable, :unitPrice, :pre, :post,
                                :occurredAt)
                        """)
                .param("id", record.lineBusinessId())
                .param("referenceId", record.referenceBusinessId())
                .param("chainId", record.responsibilityChainBusinessId())
                .param("costLineKey", record.costLineKey())
                .param("businessDate", java.sql.Date.valueOf(record.businessDate()))
                .param("allocated", record.allocatedWeight())
                .param("exempt", record.exemptWeight())
                .param("chargeable", record.chargeableWeight())
                .param("unitPrice", record.glueUnitPrice())
                .param("pre", record.preAmount())
                .param("post", record.postAmount())
                .param("occurredAt", JdbcTimes.toUtc(record.occurredAt()))
                .update();
    }

    @Override
    public List<LineRecord> listLines(String referenceBusinessId) {
        return jdbc.sql("""
                        SELECT line_business_id, reference_business_id, responsibility_chain_business_id,
                               cost_line_key, business_date, allocated_weight, exempt_weight,
                               chargeable_weight, glue_unit_price, pre_amount, post_amount, occurred_at
                        FROM finance_payroll_glue_month_lines
                        WHERE reference_business_id = :id ORDER BY business_date, responsibility_chain_business_id
                        """)
                .param("id", referenceBusinessId)
                .query((resultSet, rowNumber) -> new LineRecord(
                        resultSet.getString("line_business_id"),
                        resultSet.getString("reference_business_id"),
                        resultSet.getString("responsibility_chain_business_id"),
                        resultSet.getString("cost_line_key"),
                        resultSet.getObject("business_date", LocalDate.class),
                        resultSet.getBigDecimal("allocated_weight"),
                        resultSet.getBigDecimal("exempt_weight"),
                        resultSet.getBigDecimal("chargeable_weight"),
                        resultSet.getBigDecimal("glue_unit_price"),
                        resultSet.getBigDecimal("pre_amount"),
                        resultSet.getBigDecimal("post_amount"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "occurred_at", java.time.LocalDateTime.class))))
                .list();
    }

    private static PolicyRecord mapPolicy(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new PolicyRecord(
                resultSet.getString("policy_business_id"),
                resultSet.getObject("effective_month", LocalDate.class),
                resultSet.getLong("version"),
                resultSet.getBigDecimal("exempt_weight_grams"),
                resultSet.getString("reason_text"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", java.time.LocalDateTime.class)),
                resultSet.getString("operator_id"));
    }
}
