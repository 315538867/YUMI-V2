package com.yumi.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.calculation.api.TaskKind;
import com.yumi.calculation.api.TeamWorkloadInput;
import com.yumi.calculation.api.TeamWorkloadResult;
import com.yumi.calculation.api.WorkloadCalculator;
import com.yumi.calculation.api.WorkloadComparison;
import com.yumi.calculation.api.WorkloadInput;
import com.yumi.calculation.api.WorkloadResult;
import com.yumi.platform.api.value.DurationMinutes;
import com.yumi.platform.api.value.DurationSeconds;
import com.yumi.platform.api.value.EfficiencyFactor;
import com.yumi.platform.api.value.Quantity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 个人、小组与已核验/预留负载纯计算（CA-02）。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.3—§4.5。关键断言：
 * <ul>
 *   <li>60 秒已做 + 60 秒预留、效率 1.3：整体 120 秒 → ceil(120 ÷ 1.3) = 93 秒，不重复取整；</li>
 *   <li>其他小组任务录入 60 分钟 → 3600 秒，不再除效率；</li>
 *   <li>剩余秒数取 {@code max(0, …)} 但保留原始占用与超出信号，不掩盖超载；</li>
 *   <li>小组可用秒数取成员剩余最小值。</li>
 * </ul>
 */
class WorkloadCalculatorTest {

    private final WorkloadCalculator calculator = WorkloadCalculator.create();

    @Nested
    @DisplayName("个人任务")
    class IndividualContract {

        @Test
        @DisplayName("按明细计划数量 × 单件标准分钟 × 60 汇总")
        void sumsItemStandards() {
            WorkloadResult result = calculator.assessIndividual(new WorkloadInput(
                    TaskKind.NORMAL_PRODUCTION,
                    List.of(
                            new WorkloadInput.ItemStandard(Quantity.of(2), DurationMinutes.of(10)),
                            new WorkloadInput.ItemStandard(Quantity.of(1), DurationMinutes.of(5))),
                    null,
                    DurationMinutes.of(480),
                    DurationSeconds.of(0),
                    false));

            assertThat(result.requiredSeconds().value()).isEqualTo(2 * 10 * 60 + 1 * 5 * 60);
            assertThat(result.dailyEffectiveSeconds().value()).isEqualTo(28_800);
            assertThat(result.remainingSecondsBefore().value()).isEqualTo(28_800);
            assertThat(result.comparison()).isEqualTo(WorkloadComparison.UNDER_STANDARD);
        }

        @Test
        @DisplayName("其他任务使用整体分钟，不乘数量、不再除效率")
        void otherTaskUsesOverallMinutes() {
            WorkloadResult result = calculator.assessIndividual(new WorkloadInput(
                    TaskKind.OTHER_TASK, List.of(), DurationMinutes.of(60), DurationMinutes.of(480),
                    DurationSeconds.of(0), false));

            assertThat(result.requiredSeconds().value()).isEqualTo(3600);
        }

        @Test
        @DisplayName("比较结果区分小于、等于、大于剩余秒数")
        void comparisonBoundaries() {
            assertThat(comparisonFor(60, 120)).isEqualTo(WorkloadComparison.UNDER_STANDARD);
            assertThat(comparisonFor(120, 120)).isEqualTo(WorkloadComparison.WITHIN_STANDARD);
            assertThat(comparisonFor(180, 120)).isEqualTo(WorkloadComparison.OVER_STANDARD);
        }

        @Test
        @DisplayName("已占用超过每日有效时不掩盖超载：剩余为 0 且超出信号为真")
        void overOccupiedIsExposed() {
            WorkloadResult result = calculator.assessIndividual(new WorkloadInput(
                    TaskKind.NORMAL_PRODUCTION,
                    List.of(new WorkloadInput.ItemStandard(Quantity.of(1), DurationMinutes.of(60))),
                    null,
                    DurationMinutes.of(480),
                    DurationSeconds.of(30_000),
                    false));

            assertThat(result.remainingSecondsBefore().value()).isZero();
            assertThat(result.exceeded()).isTrue();
            assertThat(result.occupiedSecondsBefore().value()).isEqualTo(30_000);
            assertThat(result.comparison()).isEqualTo(WorkloadComparison.OVER_STANDARD);
        }

        @Test
        @DisplayName("非法输入明确报错：缺少明细、明细标准为 0、数量为负")
        void invalidInputs() {
            assertThatThrownBy(() -> new WorkloadInput(
                    TaskKind.NORMAL_PRODUCTION, List.of(), null, DurationMinutes.of(480),
                    DurationSeconds.of(0), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("至少有一条明细");

            assertThatThrownBy(() -> new WorkloadInput.ItemStandard(Quantity.of(1), DurationMinutes.of(0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("必须大于 0");

            assertThatThrownBy(() -> new WorkloadInput.ItemStandard(Quantity.of(-1), DurationMinutes.of(10)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** 用“数量 × 1 分钟”构造指定标准秒数，并通过调整已占用秒数制造指定剩余秒数。 */
        private WorkloadComparison comparisonFor(long requiredSeconds, long remainingSeconds) {
            WorkloadInput input = new WorkloadInput(
                    TaskKind.NORMAL_PRODUCTION,
                    List.of(new WorkloadInput.ItemStandard(
                            Quantity.of(requiredSeconds / 60),
                            DurationMinutes.of(1))),
                    null,
                    DurationMinutes.of(480),
                    DurationSeconds.of(28_800 - remainingSeconds),
                    false);
            return calculator.assessIndividual(input).comparison();
        }
    }

    @Nested
    @DisplayName("小组任务")
    class TeamContract {

        @Test
        @DisplayName("60 已做 + 60 预留、效率 1.3 得 93 秒，不重复取整")
        void roundsOnceAtTaskLevel() {
            TeamWorkloadResult result = calculator.assessTeam(new TeamWorkloadInput(
                    new WorkloadInput(
                            TaskKind.NORMAL_PRODUCTION,
                            List.of(
                                    new WorkloadInput.ItemStandard(Quantity.of(1), DurationMinutes.of(1)),
                                    new WorkloadInput.ItemStandard(Quantity.of(1), DurationMinutes.of(1))),
                            null,
                            DurationMinutes.of(480),
                            DurationSeconds.of(60),
                            true),
                    EfficiencyFactor.of(new BigDecimal("1.3")),
                    List.of(DurationSeconds.of(60))));

            assertThat(result.overall().requiredSeconds().value())
                    .as("ceil(120 ÷ 1.3) = 93，不能逐明细先取整")
                    .isEqualTo(93);
            assertThat(result.members()).hasSize(1);
            assertThat(result.members().getFirst().remainingSeconds().value()).isEqualTo(28_740);
            assertThat(result.members().getFirst().addedSeconds().value()).isEqualTo(93);
        }

        @Test
        @DisplayName("其他小组任务录入 60 分钟仍得 3600 秒，不除效率")
        void otherTeamTaskIsNotDividedByEfficiency() {
            TeamWorkloadResult result = calculator.assessTeam(new TeamWorkloadInput(
                    new WorkloadInput(
                            TaskKind.OTHER_TASK, List.of(), DurationMinutes.of(60), DurationMinutes.of(480),
                            DurationSeconds.of(0), true),
                    null,
                    List.of(DurationSeconds.of(0), DurationSeconds.of(60))));

            assertThat(result.overall().requiredSeconds().value()).isEqualTo(3600);
        }

        @Test
        @DisplayName("小组可用秒数取成员剩余最小值")
        void availableSecondsIsMinimumMemberRemaining() {
            TeamWorkloadResult result = calculator.assessTeam(new TeamWorkloadInput(
                    new WorkloadInput(
                            TaskKind.NORMAL_PRODUCTION,
                            List.of(new WorkloadInput.ItemStandard(Quantity.of(1), DurationMinutes.of(10))),
                            null,
                            DurationMinutes.of(480),
                            DurationSeconds.of(0),
                            true),
                    EfficiencyFactor.of(new BigDecimal("1.2")),
                    List.of(DurationSeconds.of(0), DurationSeconds.of(28_700), DurationSeconds.of(100))));

            assertThat(result.overall().dailyEffectiveSeconds().value()).isEqualTo(28_800);
            assertThat(result.availableSeconds().value())
                    .as("小组可用秒数取成员剩余最小值")
                    .isEqualTo(100);
            assertThat(result.members().stream()
                    .mapToLong(member -> member.remainingSeconds().value())
                    .min()
                    .orElseThrow()).isEqualTo(100);
        }

        @Test
        @DisplayName("成员顺序不影响整体结果")
        void memberOrderDoesNotMatter() {
            TeamWorkloadInput first = new TeamWorkloadInput(
                    new WorkloadInput(
                            TaskKind.NORMAL_PRODUCTION,
                            List.of(new WorkloadInput.ItemStandard(Quantity.of(1), DurationMinutes.of(10))),
                            null, DurationMinutes.of(480), DurationSeconds.of(0), true),
                    EfficiencyFactor.of(new BigDecimal("1.2")),
                    List.of(DurationSeconds.of(10), DurationSeconds.of(20), DurationSeconds.of(30)));
            TeamWorkloadInput second = new TeamWorkloadInput(
                    first.base(),
                    first.efficiencyFactor(),
                    List.of(DurationSeconds.of(30), DurationSeconds.of(10), DurationSeconds.of(20)));

            assertThat(calculator.assessTeam(first).overall().requiredSeconds())
                    .isEqualTo(calculator.assessTeam(second).overall().requiredSeconds());
        }

        @Test
        @DisplayName("零成员或效率不合法时明确拒绝")
        void invalidTeamInput() {
            assertThatThrownBy(() -> new TeamWorkloadInput(
                    new WorkloadInput(
                            TaskKind.NORMAL_PRODUCTION,
                            List.of(new WorkloadInput.ItemStandard(Quantity.of(1), DurationMinutes.of(10))),
                            null, DurationMinutes.of(480), DurationSeconds.of(0), true),
                    EfficiencyFactor.of(new BigDecimal("1.2")),
                    List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("至少一名成员");

            assertThatThrownBy(() -> EfficiencyFactor.of(new BigDecimal("0.9")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("大于 1");
        }
    }
}
