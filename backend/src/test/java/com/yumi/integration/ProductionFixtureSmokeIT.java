package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumi.production.api.ProductionDemandRef;
import com.yumi.production.api.TargetProcess;
import com.yumi.support.AbstractProductionFixtureTest;
import com.yumi.workforce.api.EmployeeRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 生产测试装配自检：管理员会话、人员与需求都走真实服务。 */
class ProductionFixtureSmokeIT extends AbstractProductionFixtureTest {

    @Test
    @DisplayName("测试装配可用：真实管理员会话、人员资格与生产需求")
    void fixtureWorks() {
        EmployeeRef employee = hireEmployee("张三", "MAKING");
        ProductionDemandRef demand = createNewProductDemand("ORD-SMOKE-1", TargetProcess.MAKING, 10);

        assertThat(adminToken).isNotBlank();
        assertThat(employee.employeeNo()).startsWith("E-");
        assertThat(demand.requiredQuantity()).isEqualTo(10);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM workforce_qualifications").query(Long.class).single())
                .isEqualTo(1);
    }
}
