package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.finance.payroll.api.GlueAllowancePolicyRef;
import com.yumi.finance.payroll.api.GlueMonthReferenceView;
import com.yumi.finance.payroll.api.PayrollGlueAllowancePolicyService;
import com.yumi.finance.payroll.api.PayrollGlueAllowanceQuery;
import com.yumi.finance.payroll.api.PublishGlueAllowancePolicyCommand;
import com.yumi.finance.payroll.api.RunGlueMonthReferenceCommand;
import com.yumi.platform.api.value.WeightGram;
import com.yumi.support.AbstractProductionFixtureTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 胶水自然月设置与减免参考：完整月重算、缺配置不填零、迟到来源使参考过期。
 *
 * <p>规则来源：`docs/一期/13-财务结算模块.md` §5.1.3。真实 MySQL 验证：
 * 100g 标准下 60g/80g 两行分别减免 60g、40g；拆分期间不重复；缺配置标不完整。
 */
class GlueAllowancePolicyIT extends AbstractProductionFixtureTest {

    private static final String EMPLOYEE = "EMP-GLUE-1";
    private static final LocalDate OCTOBER = LocalDate.parse("2026-10-01");

    @Autowired
    private PayrollGlueAllowancePolicyService policies;

    @Autowired
    private PayrollGlueAllowanceQuery glueQuery;

    @Test
    @DisplayName("100g 标准下 60g/80g 依次减免 60g、40g，参考金额与规范一致")
    void fullMonthReferenceAppliesAllowanceInStableOrder() {
        GlueAllowancePolicyRef policy = asAdmin(() -> policies.publish(
                new PublishGlueAllowancePolicyCommand(
                        uniqueKey("GLUE-POLICY"), OCTOBER, WeightGram.ofGrams("100.0000"),
                        "十月标准", 0)));
        assertThat(policy.version()).isEqualTo(1);
        assertThat(policy.guardVersion()).isEqualTo(1);

        seedLine("RCH-1", "GLUE-1", 1, "60.0000", "0.1000", LocalDate.parse("2026-10-05"), 1001);
        seedLine("RCH-2", "GLUE-2", 1, "80.0000", "0.2000", LocalDate.parse("2026-10-06"), 1002);

        GlueMonthReferenceView reference = asAdmin(() -> glueQuery.runMonthReference(
                new RunGlueMonthReferenceCommand(
                        uniqueKey("GLUE-RUN"), EMPLOYEE, OCTOBER)));

        assertThat(reference.status()).isEqualTo("READY");
        assertThat(reference.stale()).isFalse();
        assertThat(reference.allocatedWeight()).isEqualByComparingTo("140.0000");
        assertThat(reference.exemptWeight()).isEqualByComparingTo("100.0000");
        assertThat(reference.lines()).hasSize(2);
        assertThat(reference.lines().get(0)).satisfies(line -> {
            assertThat(line.allocatedWeight()).isEqualByComparingTo("60.0000");
            assertThat(line.exemptWeight()).isEqualByComparingTo("60.0000");
            assertThat(line.chargeableWeight()).isEqualByComparingTo("0.0000");
            assertThat(line.preAmount()).isEqualByComparingTo("6.0000");
            assertThat(line.postAmount()).isEqualByComparingTo("0.0000");
        });
        assertThat(reference.lines().get(1)).satisfies(line -> {
            assertThat(line.allocatedWeight()).isEqualByComparingTo("80.0000");
            assertThat(line.exemptWeight()).isEqualByComparingTo("40.0000");
            assertThat(line.chargeableWeight()).isEqualByComparingTo("40.0000");
            assertThat(line.preAmount()).isEqualByComparingTo("16.0000");
            assertThat(line.postAmount()).isEqualByComparingTo("8.0000");
        });
        // 拆期间不重复：再次运行仍只有一份月参考
        asAdmin(() -> glueQuery.runMonthReference(new RunGlueMonthReferenceCommand(
                uniqueKey("GLUE-RUN"), EMPLOYEE, OCTOBER)));
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM finance_payroll_glue_month_references
                        """).query(Long.class).single()).isEqualTo(1);
        assertThat(asAdmin(() -> glueQuery.getMonthReference(EMPLOYEE, OCTOBER)).exemptWeight())
                .isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("缺胶水配置时不填零，参考标不完整且不生成减免行")
    void missingPolicyMarksReferenceIncomplete() {
        seedLine("RCH-NOV", "GLUE-NOV", 1, "60.0000", "0.1000",
                LocalDate.parse("2026-11-05"), 1101);

        GlueMonthReferenceView reference = asAdmin(() -> glueQuery.runMonthReference(
                new RunGlueMonthReferenceCommand(
                        uniqueKey("GLUE-RUN"), EMPLOYEE, LocalDate.parse("2026-11-01"))));

        assertThat(reference.status()).isEqualTo("INCOMPLETE");
        assertThat(reference.incompleteReason()).isEqualTo("GLUE_ALLOWANCE_POLICY_MISSING");
        assertThat(reference.exemptWeight()).isEqualByComparingTo("0");
        assertThat(reference.lines()).isEmpty();
        assertThat(reference.allocatedWeight()).isEqualByComparingTo("60.0000");
    }

    @Test
    @DisplayName("迟到责任来源使月参考过期；配置追加版本也要求重新核对")
    void lateSourceAndNewPolicyMakeReferenceStale() {
        asAdmin(() -> policies.publish(new PublishGlueAllowancePolicyCommand(
                uniqueKey("GLUE-POLICY"), OCTOBER, WeightGram.ofGrams("100.0000"),
                "十月标准", 0)));
        seedLine("RCH-1", "GLUE-1", 1, "60.0000", "0.1000", LocalDate.parse("2026-10-05"), 1001);
        asAdmin(() -> glueQuery.runMonthReference(new RunGlueMonthReferenceCommand(
                uniqueKey("GLUE-RUN"), EMPLOYEE, OCTOBER)));

        seedLine("RCH-LATE", "GLUE-LATE", 1, "30.0000", "0.2000",
                LocalDate.parse("2026-10-20"), 1003);
        assertThat(asAdmin(() -> glueQuery.getMonthReference(EMPLOYEE, OCTOBER)).stale()).isTrue();

        GlueMonthReferenceView rerun = asAdmin(() -> glueQuery.runMonthReference(
                new RunGlueMonthReferenceCommand(
                        uniqueKey("GLUE-RUN"), EMPLOYEE, OCTOBER)));
        assertThat(rerun.stale()).isFalse();
        assertThat(rerun.allocatedWeight()).isEqualByComparingTo("90.0000");
        assertThat(rerun.exemptWeight()).isEqualByComparingTo("90.0000");
        assertThat(rerun.lines()).hasSize(2);
        assertThat(rerun.lines().get(1).postAmount()).isEqualByComparingTo("0.0000");

        GlueAllowancePolicyRef second = asAdmin(() -> policies.publish(
                new PublishGlueAllowancePolicyCommand(
                        uniqueKey("GLUE-POLICY"), OCTOBER, WeightGram.ofGrams("80.0000"),
                        "下调标准", 1)));
        assertThat(second.version()).isEqualTo(2);
        assertThat(asAdmin(() -> glueQuery.getPolicy(OCTOBER)).exemptWeightGrams().value())
                .isEqualByComparingTo("80.0000");
        assertThat(asAdmin(() -> glueQuery.getMonthReference(EMPLOYEE, OCTOBER)).stale()).isTrue();
    }

    @Test
    @DisplayName("显式零标准合法，未配置不等于零")
    void explicitZeroIsDifferentFromMissingConfiguration() {
        asAdmin(() -> policies.publish(new PublishGlueAllowancePolicyCommand(
                uniqueKey("GLUE-POLICY"), LocalDate.parse("2026-12-01"),
                WeightGram.ofGrams("0.0000"), "本月不减免", 0)));
        seedLine("RCH-DEC", "GLUE-DEC", 1, "60.0000", "0.1000",
                LocalDate.parse("2026-12-05"), 1201);

        GlueMonthReferenceView reference = asAdmin(() -> glueQuery.runMonthReference(
                new RunGlueMonthReferenceCommand(
                        uniqueKey("GLUE-RUN"), EMPLOYEE, LocalDate.parse("2026-12-01"))));
        assertThat(reference.status()).isEqualTo("READY");
        assertThat(reference.exemptWeight()).isEqualByComparingTo("0");
        assertThat(reference.lines().getFirst().postAmount()).isEqualByComparingTo("6.0000");
    }

    private static String uniqueKey(String suffix) {
        return "IT-GLUE-" + java.util.UUID.randomUUID() + "-" + suffix;
    }

    private void seedLine(
            String chainId, String dispositionId, int quantity, String gramsPerPiece,
            String unitPrice, LocalDate businessDate, long feedSequence) {
        jdbc.sql("""
                        INSERT INTO finance_payroll_responsibility_entries (
                            entry_business_id, source_revision_business_id, feed_sequence,
                            responsibility_chain_business_id, disposition_fact_business_id,
                            effect_type, responsibility_type, responsible_subject_business_id,
                            quantity, glue_grams_per_piece, glue_unit_price_per_gram,
                            glue_eligible, business_date, occurred_at, ingested_at)
                        VALUES (:id, :revisionId, :sequence, :chainId, :dispositionId, 'APPLY',
                                'EMPLOYEE', :employeeId, :quantity, :grams, :price, 1,
                                :businessDate, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """)
                .param("id", "PRE-TEST-" + feedSequence)
                .param("revisionId", "RSV-TEST-" + feedSequence)
                .param("sequence", feedSequence)
                .param("chainId", chainId)
                .param("dispositionId", dispositionId)
                .param("employeeId", EMPLOYEE)
                .param("quantity", quantity)
                .param("grams", new BigDecimal(gramsPerPiece))
                .param("price", new BigDecimal(unitPrice))
                .param("businessDate", java.sql.Date.valueOf(businessDate))
                .update();
    }
}
