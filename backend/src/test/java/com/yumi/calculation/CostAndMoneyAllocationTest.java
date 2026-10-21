package com.yumi.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.calculation.api.AllocationResult;
import com.yumi.calculation.api.CostAllocationResult;
import com.yumi.calculation.api.CostAmountCalculator;
import com.yumi.calculation.api.MoneyAllocationCalculator;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.Quantity;
import com.yumi.platform.api.value.SettlementMoney;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 不同精度金额乘法、分配及稳定余数（CA-03、CA-06）。
 *
 * <p>规则来源：`docs/一期/07-计算中心模块.md` §4.6—§4.8。
 * 关键断言：两位工资与四位非胶水成本各自分配守恒，输入排列不改结果，成本精度不得隐式转为结算精度。
 */
class CostAndMoneyAllocationTest {

    private final MoneyAllocationCalculator allocator = MoneyAllocationCalculator.create();

    private final CostAmountCalculator amounts = CostAmountCalculator.create();

    @Nested
    @DisplayName("金额乘法")
    class MultiplicationContract {

        @Test
        @DisplayName("单件 0.3333 元 × 3 件先乘后舍入，得到 1.00 元而不是 0.99 元")
        void settleRoundsAfterMultiplication() {
            SettlementMoney amount = amounts.multiplyToSettlement(
                    Quantity.of(3), CostAmount.of(new BigDecimal("0.3333"), CurrencyCode.CNY));

            assertThat(amount.value())
                    .as("先算 0.9999 再 HALF_UP 到 1.00，不能先把单价舍入成 0.33")
                    .isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("成本金额保留 4 位：0.3333 × 3 = 0.9999")
        void costKeepsFourDecimals() {
            CostAmount amount = amounts.multiplyCost(
                    Quantity.of(3), CostAmount.of(new BigDecimal("0.3333"), CurrencyCode.CNY));

            assertThat(amount.value()).isEqualByComparingTo("0.9999");
            assertThat(amount.value().scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("成本精度不隐式转为结算精度")
        void costDoesNotLeakIntoSettlement() {
            CostAmount cost = amounts.multiplyCost(
                    Quantity.of(3), CostAmount.of(new BigDecimal("0.3333"), CurrencyCode.CNY));
            SettlementMoney settlement = amounts.multiplyToSettlement(
                    Quantity.of(3), CostAmount.of(new BigDecimal("0.3333"), CurrencyCode.CNY));

            assertThat(cost.value().scale()).isEqualTo(4);
            assertThat(settlement.value().scale()).isEqualTo(2);
            assertThat(CostAmount.class.getDeclaredMethods())
                    .extracting(java.lang.reflect.Method::getReturnType)
                    .doesNotContain(SettlementMoney.class);
        }

        @Test
        @DisplayName("零数量为零金额；负数数量被拒绝")
        void quantityBounds() {
            assertThat(amounts.multiply(
                            Quantity.of(0), SettlementMoney.of(new BigDecimal("10"), CurrencyCode.CNY)).value())
                    .isEqualByComparingTo("0.00");
            assertThatThrownBy(() -> Quantity.of(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("结算金额均分")
    class SettlementAllocationContract {

        @Test
        @DisplayName("1000.00 元分给 3 人：前 1 人 333.34，其余 333.33，合计严格等于总额")
        void splitsWithStableRemainder() {
            AllocationResult result = allocator.splitEqually(
                    SettlementMoney.of(new BigDecimal("1000.00"), CurrencyCode.CNY),
                    List.of("EMP-001", "EMP-002", "EMP-003"));

            assertThat(result.shares()).hasSize(3);
            assertThat(result.shares().get(0).amount().value()).isEqualByComparingTo("333.34");
            assertThat(result.shares().get(1).amount().value()).isEqualByComparingTo("333.33");
            assertThat(result.shares().get(2).amount().value()).isEqualByComparingTo("333.33");
            assertThat(result.remainderUnits()).isEqualTo(1);

            BigDecimal sum = result.shares().stream()
                    .map(share -> share.amount().value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("成员输入顺序不影响分配；结果按稳定标识解释")
        void orderDoesNotChangeTotal() {
            SettlementMoney total = SettlementMoney.of(new BigDecimal("100.01"), CurrencyCode.CNY);
            List<String> ordered = List.of("EMP-001", "EMP-002", "EMP-003");

            AllocationResult forward = allocator.splitEqually(total, ordered);
            List<String> shuffled = new java.util.ArrayList<>(ordered);
            Collections.reverse(shuffled);
            AllocationResult sortedInternally = allocator.splitEqually(total, shuffled);

            assertThat(sortedInternally.shares())
                    .extracting(AllocationResult.Share::employeeId)
                    .containsExactlyElementsOf(ordered);
            assertThat(sortedInternally.shares().get(0).amount().value())
                    .isEqualByComparingTo(forward.shares().get(0).amount().value());
        }

        @Test
        @DisplayName("零成员被拒绝；单人获得全额")
        void memberBounds() {
            assertThatThrownBy(() -> allocator.splitEqually(
                    SettlementMoney.of(BigDecimal.ONE, CurrencyCode.CNY), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("至少一名");

            AllocationResult single = allocator.splitEqually(
                    SettlementMoney.of(new BigDecimal("99.99"), CurrencyCode.CNY), List.of("EMP-001"));
            assertThat(single.shares().getFirst().amount().value()).isEqualByComparingTo("99.99");
        }

        @Test
        @DisplayName("小额总额也能精确分配，不产生分币差")
        void tinyAmountsHaveNoPennyDrift() {
            AllocationResult result = allocator.splitEqually(
                    SettlementMoney.of(new BigDecimal("0.05"), CurrencyCode.CNY),
                    List.of("EMP-001", "EMP-002", "EMP-003"));

            assertThat(result.shares())
                    .extracting(share -> share.amount().value().toPlainString())
                    .containsExactly("0.02", "0.02", "0.01");
        }
    }

    @Nested
    @DisplayName("成本金额分配")
    class CostAllocationContract {

        @Test
        @DisplayName("1.0000 元分给 3 人：按 0.0001 元处理余数，合计严格等于总额")
        void splitsCostByTenThousandth() {
            CostAllocationResult result = allocator.splitCostEqually(
                    CostAmount.of(new BigDecimal("1.0000"), CurrencyCode.CNY),
                    List.of("EMP-001", "EMP-002", "EMP-003"));

            assertThat(result.shares()).hasSize(3);
            BigDecimal sum = result.shares().stream()
                    .map(share -> share.amount().value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo("1.0000");
            assertThat(result.shares().getFirst().amount().value()).isEqualByComparingTo("0.3334");
        }

        @Test
        @DisplayName("三位员工 1 克胶水、每克 0.001 元：重量与金额按规范舍入")
        void glueMicroAmount() {
            // 重量分配是单独的胶水计算；这里验证金额边界：0.001 × 0.3334 = 0.0003334 → 0.0003
            CostAmount perGram = CostAmount.of(new BigDecimal("0.001"), CurrencyCode.CNY);
            CostAmount line = CostAmount.of(
                    perGram.value().multiply(new BigDecimal("0.3334")), CurrencyCode.CNY);

            assertThat(line.value()).isEqualByComparingTo("0.0003");
        }
    }
}
