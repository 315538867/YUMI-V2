package com.yumi.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.calculation.api.MonthlyGlueAllowanceCalculator;
import com.yumi.calculation.api.MonthlyGlueAllowanceInput;
import com.yumi.calculation.api.MonthlyGlueAllowanceInput.GlueResponsibilityLine;
import com.yumi.calculation.api.MonthlyGlueAllowanceResult;
import com.yumi.platform.api.value.WeightGram;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 月度胶水重量分配、稳定顺序减免与逐行舍入（CA-04、CA-05）。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.10。关键断言：
 * <ul>
 *   <li>月标准 100 克，先 60 克@0.10、后 80 克@0.20：分别免扣 60 克与 40 克，后者参考 8 元；</li>
 *   <li>免除 60 克行后完整重算，80 克全部获得减免，不沿用上次剩余；</li>
 *   <li>三人分 1 克、每克 0.001 元、免扣为零：重量 0.3334/0.3333/0.3333，金额各 0.0003，
 *       合计 0.0009；</li>
 *   <li>缺依据的行返回原因与空金额，不填假零，不影响其他行。</li>
 * </ul>
 */
class MonthlyGlueAllowanceCalculatorTest {

    private final MonthlyGlueAllowanceCalculator calculator = MonthlyGlueAllowanceCalculator.create();

    @Nested
    @DisplayName("按月标准减免")
    class AllowanceContract {

        @Test
        @DisplayName("60 克@0.10 与 80 克@0.20 混合价格：免扣 60 克与 40 克，后者参考 8 元")
        void mixedPricesUseStableOrder() {
            MonthlyGlueAllowanceResult result = calculator.calculate(new MonthlyGlueAllowanceInput(
                    "EMP-001",
                    "GLUE-2026-09-v1",
                    WeightGram.ofGrams("100"),
                    List.of(
                            line("LINE-1", LocalDate.parse("2026-09-01"), "60", "0.10"),
                            line("LINE-2", LocalDate.parse("2026-09-05"), "80", "0.20"))));

            assertThat(result.lines()).hasSize(2);
            var first = result.lines().get(0);
            var second = result.lines().get(1);

            assertThat(first.allowedWeight().value()).isEqualByComparingTo("60.0000");
            assertThat(first.chargedWeight().value()).isEqualByComparingTo("0.0000");
            assertThat(first.afterReference().value()).isEqualByComparingTo("0.0000");

            assertThat(second.allowedWeight().value()).isEqualByComparingTo("40.0000");
            assertThat(second.chargedWeight().value()).isEqualByComparingTo("40.0000");
            assertThat(second.afterReference().value()).isEqualByComparingTo("8.0000");

            assertThat(result.totalAllowedWeight().value()).isEqualByComparingTo("100.0000");
            assertThat(result.complete()).isTrue();
        }

        @Test
        @DisplayName("免除 60 克行后完整重算：80 克全部获得减免，不沿用上次剩余")
        void recomputeAfterSourceRemoved() {
            MonthlyGlueAllowanceResult afterRemoval = calculator.calculate(new MonthlyGlueAllowanceInput(
                    "EMP-001",
                    "GLUE-2026-09-v1",
                    WeightGram.ofGrams("100"),
                    List.of(line("LINE-2", LocalDate.parse("2026-09-05"), "80", "0.20"))));

            var onlyLine = afterRemoval.lines().getFirst();
            assertThat(onlyLine.allowedWeight().value()).isEqualByComparingTo("80.0000");
            assertThat(onlyLine.chargedWeight().value()).isEqualByComparingTo("0.0000");
            assertThat(onlyLine.afterReference().value()).isEqualByComparingTo("0.0000");
            assertThat(afterRemoval.totalAllowedWeight().value()).isEqualByComparingTo("80.0000");
        }

        @Test
        @DisplayName("行顺序由业务日期与稳定标识决定，输入顺序不影响结果")
        void inputOrderDoesNotMatter() {
            List<GlueResponsibilityLine> lines = new ArrayList<>(List.of(
                    line("LINE-1", LocalDate.parse("2026-09-01"), "60", "0.10"),
                    line("LINE-2", LocalDate.parse("2026-09-05"), "80", "0.20")));

            MonthlyGlueAllowanceResult forward = calculator.calculate(new MonthlyGlueAllowanceInput(
                    "EMP-001", "P1", WeightGram.ofGrams("100"), lines));
            java.util.Collections.reverse(lines);
            MonthlyGlueAllowanceResult reversed = calculator.calculate(new MonthlyGlueAllowanceInput(
                    "EMP-001", "P1", WeightGram.ofGrams("100"), lines));

            assertThat(reversed.lines())
                    .extracting(MonthlyGlueAllowanceResult.GlueLineResult::costLineId)
                    .containsExactly("LINE-1", "LINE-2");
            assertThat(reversed.lines().get(1).afterReference().value())
                    .isEqualByComparingTo(forward.lines().get(1).afterReference().value());
        }

        @Test
        @DisplayName("分配重量 = 减免重量 + 计价重量，且减免不超过标准")
        void perLineEquationHolds() {
            MonthlyGlueAllowanceResult result = calculator.calculate(new MonthlyGlueAllowanceInput(
                    "EMP-001", "P1", WeightGram.ofGrams("50"),
                    List.of(
                            line("LINE-1", LocalDate.parse("2026-09-01"), "30", "0.10"),
                            line("LINE-2", LocalDate.parse("2026-09-02"), "30", "0.10"),
                            line("LINE-3", LocalDate.parse("2026-09-03"), "30", "0.10"))));

            assertThat(result.lines()).allSatisfy(line -> assertThat(
                    line.allowedWeight().value().add(line.chargedWeight().value()))
                    .isEqualByComparingTo(line.allocatedWeight().value()));
            assertThat(result.totalAllowedWeight().value()).isEqualByComparingTo("50.0000");
        }
    }

    @Nested
    @DisplayName("微量金额与逐行舍入")
    class RoundingContract {

        @Test
        @DisplayName("三人分 1 克：重量按 0.0001g 分配为 0.3334/0.3333/0.3333，合计守恒")
        void weightAllocationConserves() {
            var allocation = com.yumi.calculation.api.GlueWeightAllocator.create()
                    .splitEqually(WeightGram.ofGrams("1"), List.of("EMP-003", "EMP-001", "EMP-002"));

            assertThat(allocation.shares())
                    .extracting(share -> share.employeeId() + "=" + share.weight().value().toPlainString())
                    .containsExactly("EMP-001=0.3334", "EMP-002=0.3333", "EMP-003=0.3333");

            java.math.BigDecimal sum = allocation.shares().stream()
                    .map(share -> share.weight().value())
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("1.0000");
        }

        @Test
        @DisplayName("三人分 1 克、每克 0.001 元：各 0.0003 元，合计 0.0009 元")
        void microAmountLines() {
            var allocation = com.yumi.calculation.api.GlueWeightAllocator.create()
                    .splitEqually(WeightGram.ofGrams("1"), List.of("EMP-001", "EMP-002", "EMP-003"));
            List<WeightGram> weights = allocation.shares().stream()
                    .map(com.yumi.calculation.api.GlueWeightAllocator.WeightAllocationResult.Share::weight)
                    .toList();

            List<MonthlyGlueAllowanceResult.GlueLineResult> allLines = new ArrayList<>();
            for (int index = 0; index < weights.size(); index++) {
                MonthlyGlueAllowanceResult result = calculator.calculate(new MonthlyGlueAllowanceInput(
                        "EMP-00" + (index + 1),
                        "P1",
                        WeightGram.ofGrams("0"),
                        List.of(new GlueResponsibilityLine(
                                LocalDate.parse("2026-09-01"),
                                "CHAIN-1",
                                "LINE-" + (index + 1),
                                weights.get(index),
                                new BigDecimal("0.001")))));
                allLines.addAll(result.lines());
            }

            assertThat(allLines).hasSize(3);
            assertThat(allLines).allSatisfy(line ->
                    assertThat(line.afterReference().value()).isEqualByComparingTo("0.0003"));

            java.math.BigDecimal total = allLines.stream()
                    .map(line -> line.afterReference().value())
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            assertThat(total).as("合计只相加已舍入行：0.0003 × 3 = 0.0009").isEqualByComparingTo("0.0009");
        }

        @Test
        @DisplayName("展示的减免金额取减免前后之差，不再以汇总重量重算")
        void allowanceAmountIsDifference() {
            MonthlyGlueAllowanceResult result = calculator.calculate(new MonthlyGlueAllowanceInput(
                    "EMP-001", "P1", WeightGram.ofGrams("100"),
                    List.of(line("LINE-1", LocalDate.parse("2026-09-01"), "105", "0.3333"))));

            var line = result.lines().getFirst();
            assertThat(line.beforeReference().value()).isEqualByComparingTo("34.9965");
            assertThat(line.afterReference().value()).isEqualByComparingTo("1.6665");
            assertThat(line.allowanceAmount().value())
                    .isEqualByComparingTo(line.beforeReference().value().subtract(line.afterReference().value()));
        }
    }

    @Nested
    @DisplayName("缺依据与配置")
    class MissingContract {

        @Test
        @DisplayName("缺历史单价的行返回原因与空金额，不填假零")
        void missingPriceKeepsNullAndReason() {
            MonthlyGlueAllowanceResult result = calculator.calculate(new MonthlyGlueAllowanceInput(
                    "EMP-001", "P1", WeightGram.ofGrams("100"),
                    List.of(
                            line("LINE-1", LocalDate.parse("2026-09-01"), "60", "0.10"),
                            new GlueResponsibilityLine(
                                    LocalDate.parse("2026-09-02"), "CHAIN-1", "LINE-2",
                                    WeightGram.ofGrams("20"), null))));

            assertThat(result.complete()).isFalse();
            assertThat(result.missingLineCount()).isEqualTo(1);
            var missing = result.lines().get(1);
            assertThat(missing.beforeReference()).isNull();
            assertThat(missing.afterReference()).isNull();
            assertThat(missing.missingReason()).contains("缺历史胶水单价");
            assertThat(result.lines().get(0).hasReference()).isTrue();
        }

        @Test
        @DisplayName("缺配置时不按零处理，直接明确拒绝")
        void missingPolicyIsRejected() {
            assertThatThrownBy(() -> new MonthlyGlueAllowanceInput(
                    "EMP-001", "P1", null,
                    List.of(line("LINE-1", LocalDate.parse("2026-09-01"), "60", "0.10"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("缺配置不得按零处理");
        }

        @Test
        @DisplayName("无责任行时返回空明细与完整状态，不报错")
        void noLinesIsCompleteButEmpty() {
            MonthlyGlueAllowanceResult result = calculator.calculate(new MonthlyGlueAllowanceInput(
                    "EMP-001", "P1", WeightGram.ofGrams("100"), List.of()));

            assertThat(result.lines()).isEmpty();
            assertThat(result.complete()).isTrue();
            assertThat(result.totalAllowedWeight().value()).isEqualByComparingTo("0.0000");
        }
    }

    private static GlueResponsibilityLine line(
            String costLineId, LocalDate businessDate, String weight, String unitPrice) {
        return new GlueResponsibilityLine(
                businessDate, "CHAIN-1", costLineId, WeightGram.ofGrams(weight), new BigDecimal(unitPrice));
    }
}
