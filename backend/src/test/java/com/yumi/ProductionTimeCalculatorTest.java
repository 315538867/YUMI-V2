package com.yumi;

import com.yumi.calculation.production.ProductionTimeCalculator;
import com.yumi.calculation.production.ProductionTimeResult;
import com.yumi.calculation.production.ProductionWorkType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 阶段五 5.3 生产时间计算：正常工时、正常产能占用与返工工时。
 * 制作按「工作日分钟 × 制品有效工时率」，包装与缝边剪袋按完整工作日分钟；返工不占正常产能。
 */
class ProductionTimeCalculatorTest {

    private static final BigDecimal WORKDAY_HOURS = new BigDecimal("8.0000");
    private static final BigDecimal EFFECTIVE_RATE = new BigDecimal("0.750000");

    @Test
    void calculatesMakingCapacityWithEffectiveRate() {
        // 8 小时 × 60 分钟 × 0.75 = 360 分钟正常产能
        var result = ProductionTimeCalculator.calculateNormal(10, 6, ProductionWorkType.MAKING,
                EFFECTIVE_RATE, WORKDAY_HOURS);
        assertThat(result.plannedQuantity()).isEqualTo(10);
        assertThat(result.standardMinutes()).isEqualTo(6);
        assertThat(result.normalMinutes()).isEqualTo(60);
        assertThat(result.normalHours()).isEqualByComparingTo("1.0000");
        assertThat(result.capacityMinutes()).isEqualTo(360);
        assertThat(result.reworkMinutes()).isZero();
        assertThat(result.capacityNotice()).isNull();

        var over = ProductionTimeCalculator.calculateNormal(10, 50, ProductionWorkType.MAKING,
                EFFECTIVE_RATE, WORKDAY_HOURS);
        assertThat(over.normalMinutes()).isEqualTo(500);
        assertThat(over.capacityMinutes()).isEqualTo(360);
        assertThat(over.capacityNotice()).isEqualTo("超出当日正常产能 140 分钟");
    }

    @Test
    void calculatesPackingAndSeamWithFullWorkdayMinutes() {
        // 包装与缝边剪袋不套用制品有效工时率：8 小时 × 60 = 480 分钟
        var packing = ProductionTimeCalculator.calculateNormal(9, 50, ProductionWorkType.PACKING_BAG,
                EFFECTIVE_RATE, WORKDAY_HOURS);
        assertThat(packing.capacityMinutes()).isEqualTo(480);
        assertThat(packing.normalMinutes()).isEqualTo(450);
        assertThat(packing.capacityNotice()).isNull();

        var seam = ProductionTimeCalculator.calculateNormal(4, 5, ProductionWorkType.SEAM_CUTTING,
                EFFECTIVE_RATE, WORKDAY_HOURS);
        assertThat(seam.normalMinutes()).isEqualTo(20);
        assertThat(seam.normalHours()).isEqualByComparingTo("0.3333");
        assertThat(seam.capacityMinutes()).isEqualTo(480);

        var seamOver = ProductionTimeCalculator.calculateNormal(100, 5, ProductionWorkType.SEAM_CUTTING,
                EFFECTIVE_RATE, WORKDAY_HOURS);
        assertThat(seamOver.capacityMinutes()).isEqualTo(480);
        assertThat(seamOver.capacityNotice()).isEqualTo("超出当日正常产能 20 分钟");
    }

    @Test
    void returnsZeroNormalCapacityForRework() {
        var result = ProductionTimeCalculator.calculateRework(3, 5);
        assertThat(result.normalMinutes()).isZero();
        assertThat(result.normalHours()).isEqualByComparingTo("0.0000");
        assertThat(result.capacityMinutes()).isZero();
        assertThat(result.capacityNotice()).isNull();
        // 返工工时单独返回，不进入正常工时
        assertThat(result.reworkMinutes()).isEqualTo(15);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> ProductionTimeCalculator.calculateNormal(0, 6, ProductionWorkType.MAKING,
                EFFECTIVE_RATE, WORKDAY_HOURS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductionTimeCalculator.calculateRework(-1, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductionTimeCalculator.calculateNormal(1, 6, null,
                EFFECTIVE_RATE, WORKDAY_HOURS)).isInstanceOf(IllegalArgumentException.class);
    }
}
