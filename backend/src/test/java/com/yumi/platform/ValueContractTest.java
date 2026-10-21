package com.yumi.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.value.ContentFingerprint;
import com.yumi.platform.api.value.CostAmount;
import com.yumi.platform.api.value.CurrencyCode;
import com.yumi.platform.api.value.DurationMinutes;
import com.yumi.platform.api.value.DurationSeconds;
import com.yumi.platform.api.value.EfficiencyFactor;
import com.yumi.platform.api.value.LossRate;
import com.yumi.platform.api.value.Quantity;
import com.yumi.platform.api.value.SettlementMoney;
import com.yumi.platform.api.value.SignedCostAmount;
import com.yumi.platform.api.value.SignedQuantity;
import com.yumi.platform.api.value.SignedSettlementAmount;
import com.yumi.platform.api.value.SnapshotRef;
import com.yumi.platform.api.value.StableBusinessId;
import com.yumi.platform.api.value.WeightGram;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 数值与引用值对象契约。
 *
 * <p>规则来源：`docs/一期/04-公共业务与技术规则.md` 第 2、5、8 节。所有金额、重量、秒、数量、
 * 比例和指纹必须在值对象构造边界完成精度、符号和范围校验；非法输入明确报错，不截断、
 * 不填零、不隐式转换精度。
 *
 * <p>本测试只覆盖纯值契约，不涉及持久化、认证或业务 Feed。
 */
class ValueContractTest {

    @Nested
    @DisplayName("成本金额（DECIMAL(19,4)）")
    class CostAmountContract {

        @Test
        @DisplayName("按 HALF_UP 舍入到 4 位并携带币种")
        void roundsHalfUpToFourDecimals() {
            assertThat(CostAmount.of(new BigDecimal("1.00005"), CurrencyCode.CNY).value())
                    .isEqualByComparingTo("1.0001");
            assertThat(CostAmount.of(new BigDecimal("1.00004"), CurrencyCode.CNY).value())
                    .isEqualByComparingTo("1.0000");
            assertThat(CostAmount.of(new BigDecimal("0.00009"), CurrencyCode.CNY).value())
                    .isEqualByComparingTo("0.0001");
            assertThat(CostAmount.of(new BigDecimal("12.5"), CurrencyCode.CNY).value().scale())
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("拒绝负数与超过 DECIMAL(19,4) 范围的输入")
        void rejectsNegativeAndOverflow() {
            assertThatThrownBy(() -> CostAmount.of(new BigDecimal("-0.0001"), CurrencyCode.CNY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("负");
            assertThatThrownBy(() -> CostAmount.of(new BigDecimal("1000000000000000"), CurrencyCode.CNY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DECIMAL(19,4)");
            assertThatCode(() -> CostAmount.of(new BigDecimal("999999999999999.9999"), CurrencyCode.CNY))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("必须携带币种")
        void requiresCurrency() {
            assertThatThrownBy(() -> CostAmount.of(BigDecimal.ONE, null))
                    .isInstanceOf(NullPointerException.class);
            assertThat(CostAmount.ZERO.currency()).isEqualTo(CurrencyCode.CNY);
        }

        @Test
        @DisplayName("加法保持 4 位精度")
        void additionKeepsScale() {
            CostAmount sum = CostAmount.of(new BigDecimal("0.0001"), CurrencyCode.CNY)
                    .plus(CostAmount.of(new BigDecimal("0.0002"), CurrencyCode.CNY));
            assertThat(sum.value()).isEqualByComparingTo("0.0003");
            assertThat(sum.value().scale()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("有符号成本差额")
    class SignedCostAmountContract {

        @Test
        @DisplayName("允许负数但保持 4 位精度与范围")
        void allowsNegative() {
            assertThat(SignedCostAmount.of(new BigDecimal("-1.5")).value())
                    .isEqualByComparingTo("-1.5000");
            assertThatThrownBy(() -> SignedCostAmount.of(new BigDecimal("1000000000000000")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("有符号差额与无符号成本金额是不同的类型")
        void signedDifferenceIsADistinctType() {
            assertThat(SignedCostAmount.class).isNotEqualTo(CostAmount.class);
            assertThat(CostAmount.class.getDeclaredMethods())
                    .extracting(Method::getReturnType)
                    .doesNotContain(SignedCostAmount.class, SettlementMoney.class);
        }
    }

    @Nested
    @DisplayName("结算金额（DECIMAL(19,2)）")
    class SettlementMoneyContract {

        @Test
        @DisplayName("按 HALF_UP 舍入到分")
        void roundsHalfUpToCents() {
            assertThat(SettlementMoney.of(new BigDecimal("2.005"), CurrencyCode.CNY).value())
                    .isEqualByComparingTo("2.01");
            assertThat(SettlementMoney.of(new BigDecimal("2.004"), CurrencyCode.CNY).value())
                    .isEqualByComparingTo("2.00");
            assertThat(SettlementMoney.of(new BigDecimal("2.004"), CurrencyCode.CNY).value().scale())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("拒绝负数，且不与成本精度隐式互转")
        void rejectsNegativeAndPrecisionLeak() {
            assertThatThrownBy(() -> SettlementMoney.of(new BigDecimal("-0.01"), CurrencyCode.CNY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("负");
            assertThat(SettlementMoney.class.getDeclaredMethods())
                    .extracting(Method::getReturnType)
                    .doesNotContain(CostAmount.class, SignedCostAmount.class);
            assertThat(SettlementMoney.of(new BigDecimal("0.004"), CurrencyCode.CNY).value())
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("有符号结算差额与数量差额")
    class SignedDifferenceContract {

        @Test
        @DisplayName("允许负数并保持结算精度")
        void signedSettlementAllowsNegative() {
            assertThat(SignedSettlementAmount.of(new BigDecimal("-100.005")).value())
                    .isEqualByComparingTo("-100.01");
        }

        @Test
        @DisplayName("数量差额允许负数但正式数量不允许")
        void quantitySignRules() {
            assertThat(SignedQuantity.of(-3L).value()).isEqualTo(-3L);
            assertThatThrownBy(() -> Quantity.of(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("负");
            assertThat(Quantity.of(0L).value()).isZero();
        }
    }

    @Nested
    @DisplayName("重量（DECIMAL(19,4)）")
    class WeightContract {

        @Test
        @DisplayName("105 克损耗示例保持 4 位小数")
        void keepsGramUnit() {
            WeightGram grams = WeightGram.ofGrams(new BigDecimal("105"));
            assertThat(grams.value()).isEqualByComparingTo("105.0000");
            assertThat(grams.unit()).isEqualTo("g");
        }

        @Test
        @DisplayName("拒绝负重量与超过 4 位小数的舍入越界")
        void rejectsInvalidWeight() {
            assertThatThrownBy(() -> WeightGram.ofGrams(new BigDecimal("-0.0001")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("负");
            assertThat(WeightGram.ofGrams(new BigDecimal("0.00004")).value())
                    .isEqualByComparingTo("0.0000");
        }
    }

    @Nested
    @DisplayName("秒与分钟")
    class DurationContract {

        @Test
        @DisplayName("秒为整数且非负，溢出明确报错")
        void secondsAreIntegerAndNonNegative() {
            assertThat(DurationSeconds.of(93L).value()).isEqualTo(93L);
            assertThatThrownBy(() -> DurationSeconds.of(-1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DurationSeconds.of(Long.MAX_VALUE).plus(DurationSeconds.of(1L)))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("分钟在边界转换为秒，不重复取整")
        void minutesConvertToSecondsAtBoundary() {
            assertThat(DurationMinutes.of(60).toSeconds().value()).isEqualTo(3600L);
            assertThatThrownBy(() -> DurationMinutes.of(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(DurationSeconds.of(93L).toMinutesRoundedUp().value()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("比例")
    class RatioContract {

        @Test
        @DisplayName("损耗率范围为 [0,1)")
        void lossRateRange() {
            assertThat(LossRate.of(new BigDecimal("0.05")).value()).isEqualByComparingTo("0.05000000");
            assertThatThrownBy(() -> LossRate.of(BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[0,1)");
            assertThatThrownBy(() -> LossRate.of(new BigDecimal("-0.01")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("效率因子必须大于 1")
        void efficiencyFactorMustExceedOne() {
            assertThat(EfficiencyFactor.of(new BigDecimal("1.3")).value())
                    .isEqualByComparingTo("1.30000000");
            assertThatThrownBy(() -> EfficiencyFactor.of(BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("大于 1");
            assertThatThrownBy(() -> EfficiencyFactor.of(new BigDecimal("0.9")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("稳定标识与指纹")
    class IdentityContract {

        @Test
        @DisplayName("内容指纹为 64 位小写十六进制")
        void fingerprintFormat() {
            String sha256 = "a".repeat(64);
            assertThat(ContentFingerprint.of(sha256).value()).isEqualTo(sha256);
            assertThatThrownBy(() -> ContentFingerprint.of(sha256.toUpperCase()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ContentFingerprint.of("abc"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("稳定业务标识不超过 64 字符且不可为空白")
        void stableBusinessIdRules() {
            assertThat(StableBusinessId.of("ORD-2026-0001").value()).isEqualTo("ORD-2026-0001");
            assertThatThrownBy(() -> StableBusinessId.of(" "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> StableBusinessId.of("x".repeat(65)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("快照引用必须完整，不能只保存裸版本号")
        void snapshotRefIsComplete() {
            SnapshotRef ref = new SnapshotRef(
                    "product", "PRODUCT_SCHEME", StableBusinessId.of("SCH-1"), 3L, 3L,
                    ContentFingerprint.of("b".repeat(64)));

            assertThat(ref.ownerModule()).isEqualTo("product");
            assertThat(ref.snapshotVersion()).isEqualTo(3L);

            assertThatThrownBy(() -> new SnapshotRef(
                    "product", "PRODUCT_SCHEME", StableBusinessId.of("SCH-1"), 0L, 3L,
                    ContentFingerprint.of("b".repeat(64))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("版本");
            assertThatThrownBy(() -> new SnapshotRef(
                    "product", "PRODUCT_SCHEME", StableBusinessId.of("SCH-1"), 3L, 3L, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("币种")
    class CurrencyContract {

        @Test
        @DisplayName("币种为 3 位大写字母代码")
        void currencyCodeFormat() {
            assertThat(CurrencyCode.of("CNY").value()).isEqualTo("CNY");
            assertThatThrownBy(() -> CurrencyCode.of("cny"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CurrencyCode.of("RMB1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("不同币种金额不能直接相加")
        void differentCurrenciesCannotBeSummed() {
            CostAmount cny = CostAmount.of(BigDecimal.ONE, CurrencyCode.CNY);
            CostAmount usd = CostAmount.of(BigDecimal.ONE, CurrencyCode.of("USD"));
            assertThatThrownBy(() -> cny.plus(usd))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("币种");
        }

        @Test
        @DisplayName("金额值对象都暴露币种")
        void allMoneyTypesCarryCurrency() {
            List<Class<?>> moneyTypes = List.of(
                    CostAmount.class, SignedCostAmount.class,
                    SettlementMoney.class, SignedSettlementAmount.class);
            for (Class<?> type : moneyTypes) {
                boolean exposesCurrency = Arrays.stream(type.getRecordComponents())
                        .anyMatch(component -> component.getType().equals(CurrencyCode.class));
                assertThat(exposesCurrency)
                        .as("%s 必须携带币种", type.getSimpleName())
                        .isTrue();
            }
        }
    }
}
