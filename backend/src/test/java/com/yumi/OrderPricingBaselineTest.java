package com.yumi;

import com.yumi.calculation.order.OrderPricing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务 3.3 订单公式基线（目录标识 FP-ORDER-01..09）：人工核算的字符串算例，不启动 Spring，
 * 直接调用纯计算入口。金额逐位字符串比对，不接受浮点近似。
 */
class OrderPricingBaselineTest {

    @Test
    void itemAmountsMultiplyAndRoundHalfUpAtMoneyNode() {
        // 25.0000×10=250.0000；2.0000×4=8.0000；18.1200×10=181.2000；1.2500×4=5.0000
        var result = OrderPricing.item(new OrderPricing.ItemInputs(
                new BigDecimal("25.0000"), 10, new BigDecimal("2.0000"), 4,
                new BigDecimal("1.2500"), new BigDecimal("18.1200")));

        plain(result.goodsAmount(), "250.0000");
        plain(result.seamAmount(), "8.0000");
        plain(result.goodsCostAmount(), "181.2000");
        plain(result.seamCostAmount(), "5.0000");
    }

    @Test
    void itemAmountRoundsProductNotMultiplicand() {
        // 0.12345×3=0.37035 → scale4 HALF_UP 0.3704（若先归一乘数得 0.1235×3=0.3705）
        var result = OrderPricing.item(new OrderPricing.ItemInputs(
                new BigDecimal("0.12345"), 3, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO));

        plain(result.goodsAmount(), "0.3704");
        plain(result.seamAmount(), "0.0000");
    }

    @Test
    void orderTotalsSubtractDiscountFromReceivableOnly() {
        var item = OrderPricing.item(new OrderPricing.ItemInputs(
                new BigDecimal("25.0000"), 10, new BigDecimal("2.0000"), 4,
                new BigDecimal("1.2500"), new BigDecimal("18.1200")));
        var totals = OrderPricing.totals(List.of(item), new BigDecimal("10.0000"));

        plain(totals.goodsAmount(), "250.0000");
        plain(totals.seamAmount(), "8.0000");
        plain(totals.discountAmount(), "10.0000");
        // 应收 = 250 + 8 − 10 = 248.0000；成本 = 181.2 + 5 = 186.2000；利润 = 61.8000
        plain(totals.receivableAmount(), "248.0000");
        plain(totals.goodsCostAmount(), "181.2000");
        plain(totals.seamCostAmount(), "5.0000");
        plain(totals.costAmount(), "186.2000");
        plain(totals.profitAmount(), "61.8000");
    }

    @Test
    void orderTotalsSumMultipleItemsAndAllowNegativeProfit() {
        var first = OrderPricing.item(new OrderPricing.ItemInputs(
                new BigDecimal("10.0000"), 2, BigDecimal.ZERO, 0, BigDecimal.ZERO, new BigDecimal("8.0000")));
        var second = OrderPricing.item(new OrderPricing.ItemInputs(
                new BigDecimal("5.0000"), 1, new BigDecimal("1.5000"), 1,
                new BigDecimal("1.2500"), new BigDecimal("9.0000")));
        var totals = OrderPricing.totals(List.of(first, second), BigDecimal.ZERO);

        plain(totals.goodsAmount(), "25.0000");
        plain(totals.seamAmount(), "1.5000");
        plain(totals.receivableAmount(), "26.5000");
        // 商品成本 16+9=25.0000，缝边成本 1.2500 → 总成本 26.2500
        plain(totals.costAmount(), "26.2500");
        plain(totals.profitAmount(), "0.2500");
    }

    @Test
    void orderTotalsAllowZeroReceivableButRejectOutOfRangeDiscount() {
        var item = OrderPricing.item(new OrderPricing.ItemInputs(
                new BigDecimal("10.0000"), 1, new BigDecimal("2.0000"), 1,
                BigDecimal.ZERO, BigDecimal.ZERO));
        // 优惠等于商品金额 + 缝边收费：应收 0，利润为负
        var totals = OrderPricing.totals(List.of(item), new BigDecimal("12.0000"));
        plain(totals.receivableAmount(), "0.0000");
        plain(totals.profitAmount(), "0.0000");

        assertThatThrownBy(() -> OrderPricing.totals(List.of(item), new BigDecimal("12.0001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderPricing.totals(List.of(item), new BigDecimal("-0.0001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyOrderHasZeroTotals() {
        var totals = OrderPricing.totals(List.of(), BigDecimal.ZERO);

        assertThat(totals.goodsAmount().toPlainString()).isEqualTo("0.0000");
        assertThat(totals.receivableAmount().toPlainString()).isEqualTo("0.0000");
        assertThat(totals.costAmount().toPlainString()).isEqualTo("0.0000");
        assertThat(totals.profitAmount().toPlainString()).isEqualTo("0.0000");
    }

    private static void plain(BigDecimal actual, String expected) {
        assertThat(actual.toPlainString()).isEqualTo(expected);
    }
}
