package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.yumi.production.api.ProductionDemandParticipant;
import com.yumi.support.AbstractOrderFixtureTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 订单确认的事务边界：生产端拒绝时整笔回滚，不留半条订单。
 *
 * <p>本类用抛异常的参与端替身强制失败，只验证事务回滚这一工程性质；真实建立路径由
 * {@code OrderConfirmationIT} 覆盖，不把替身结果当作业务成功证据。
 */
class OrderRollbackIT extends AbstractOrderFixtureTest {

    @MockitoBean
    private ProductionDemandParticipant productionDemandFake;

    @Test
    @DisplayName("生产端拒绝时订单根、明细、约定与应收全部回滚")
    void failingParticipantLeavesNothing() {
        BDDMockito.given(productionDemandFake.prepareCreate(ArgumentMatchers.any()))
                .willThrow(new ApiException(
                        ApiErrorType.BUSINESS_REJECTION, "PRODUCTION_REJECTED", "生产端拒绝建需求"));

        assertThatThrownBy(() -> confirm("IDEM-ROLLBACK-1", 4))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("PRODUCTION_REJECTED"));

        assertThat(countOrders()).as("订单根未留下").isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_items").query(Long.class).single()).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_agreement_groups")
                .query(Long.class).single()).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_agreement_versions")
                .query(Long.class).single()).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_agreement_processes")
                .query(Long.class).single()).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_receivable_facts")
                .query(Long.class).single()).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_fulfillment_projections")
                .query(Long.class).single()).isZero();
        assertThat(orderJdbc.sql("SELECT COUNT(*) FROM orders_confirmation_facts")
                .query(Long.class).single()).isZero();
    }
}
