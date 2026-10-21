package com.yumi.product.infrastructure;

import com.yumi.product.application.port.BudgetRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 预算结果持久化（MySQL）：每个方案当前内容版本一行结果 + 明细行。 */
@Repository
public class JdbcBudgetRepository implements BudgetRepository {

    private final JdbcClient jdbc;

    public JdbcBudgetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BudgetRecord> findByScheme(String schemeBusinessId) {
        Optional<HeadRecord> head = jdbc.sql("""
                        SELECT budget_business_id, scheme_business_id, product_content_version,
                               scheme_content_version, formula_rule_version, rounding_version,
                               input_fingerprint, full_resource_unit_cost, studio_borne_unit_cost,
                               customer_supplied_unit_value, expected_unit_revenue, expected_unit_profit,
                               expected_profit_rate
                        FROM product_budget_results WHERE scheme_business_id = :schemeId
                        """)
                .param("schemeId", schemeBusinessId)
                .query((resultSet, rowNumber) -> new HeadRecord(
                        resultSet.getString("budget_business_id"),
                        resultSet.getString("scheme_business_id"),
                        resultSet.getLong("product_content_version"),
                        resultSet.getLong("scheme_content_version"),
                        resultSet.getString("formula_rule_version"),
                        resultSet.getString("rounding_version"),
                        resultSet.getString("input_fingerprint"),
                        resultSet.getBigDecimal("full_resource_unit_cost"),
                        resultSet.getBigDecimal("studio_borne_unit_cost"),
                        resultSet.getBigDecimal("customer_supplied_unit_value"),
                        resultSet.getBigDecimal("expected_unit_revenue"),
                        resultSet.getBigDecimal("expected_unit_profit"),
                        resultSet.getBigDecimal("expected_profit_rate")))
                .optional();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        HeadRecord record = head.orElseThrow();
        List<BudgetLineRecord> lines = jdbc.sql("""
                        SELECT line_no, process_code, component_code, cost_nature, bearer_type,
                               quantity, unit_price, full_resource_cost, studio_borne_cost
                        FROM product_budget_result_lines
                        WHERE budget_business_id = :budgetId ORDER BY line_no
                        """)
                .param("budgetId", record.budgetBusinessId())
                .query((resultSet, rowNumber) -> new BudgetLineRecord(
                        resultSet.getInt("line_no"),
                        resultSet.getString("process_code"),
                        resultSet.getString("component_code"),
                        resultSet.getString("cost_nature"),
                        resultSet.getString("bearer_type"),
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("unit_price"),
                        resultSet.getBigDecimal("full_resource_cost"),
                        resultSet.getBigDecimal("studio_borne_cost")))
                .list();
        return Optional.of(new BudgetRecord(
                record.budgetBusinessId(), record.schemeBusinessId(), record.productContentVersion(),
                record.schemeContentVersion(), record.formulaRuleVersion(), record.roundingVersion(),
                record.inputFingerprint(), record.fullResourceUnitCost(), record.studioBorneUnitCost(),
                record.customerSuppliedUnitValue(), record.expectedUnitRevenue(),
                record.expectedUnitProfit(), record.expectedProfitRate(), lines));
    }

    @Override
    public void replace(BudgetRecord record) {
        jdbc.sql("DELETE FROM product_budget_result_lines WHERE budget_business_id = :budgetId")
                .param("budgetId", record.budgetBusinessId())
                .update();
        jdbc.sql("""
                        INSERT INTO product_budget_results (
                            budget_business_id, scheme_business_id, product_content_version,
                            scheme_content_version, formula_rule_version, rounding_version,
                            input_fingerprint, full_resource_unit_cost, studio_borne_unit_cost,
                            customer_supplied_unit_value, expected_unit_revenue, expected_unit_profit,
                            expected_profit_rate, calculated_at)
                        VALUES (:budgetId, :schemeId, :productVersion, :schemeVersion, :ruleVersion,
                                :roundingVersion, :fingerprint, :fullCost, :studioCost, :customerValue,
                                :revenue, :profit, :profitRate, :now)
                        ON DUPLICATE KEY UPDATE
                            product_content_version = VALUES(product_content_version),
                            scheme_content_version = VALUES(scheme_content_version),
                            formula_rule_version = VALUES(formula_rule_version),
                            rounding_version = VALUES(rounding_version),
                            input_fingerprint = VALUES(input_fingerprint),
                            full_resource_unit_cost = VALUES(full_resource_unit_cost),
                            studio_borne_unit_cost = VALUES(studio_borne_unit_cost),
                            customer_supplied_unit_value = VALUES(customer_supplied_unit_value),
                            expected_unit_revenue = VALUES(expected_unit_revenue),
                            expected_unit_profit = VALUES(expected_unit_profit),
                            expected_profit_rate = VALUES(expected_profit_rate),
                            calculated_at = VALUES(calculated_at)
                        """)
                .param("budgetId", record.budgetBusinessId())
                .param("schemeId", record.schemeBusinessId())
                .param("productVersion", record.productContentVersion())
                .param("schemeVersion", record.schemeContentVersion())
                .param("ruleVersion", record.formulaRuleVersion())
                .param("roundingVersion", record.roundingVersion())
                .param("fingerprint", record.inputFingerprint())
                .param("fullCost", record.fullResourceUnitCost())
                .param("studioCost", record.studioBorneUnitCost())
                .param("customerValue", record.customerSuppliedUnitValue())
                .param("revenue", record.expectedUnitRevenue())
                .param("profit", record.expectedUnitProfit())
                .param("profitRate", record.expectedProfitRate())
                .param("now", JdbcTimes.toUtc(java.time.Instant.now()))
                .update();

        for (BudgetLineRecord line : record.lines()) {
            jdbc.sql("""
                            INSERT INTO product_budget_result_lines (
                                budget_business_id, line_no, process_code, component_code, cost_nature,
                                bearer_type, quantity, unit_price, full_resource_cost, studio_borne_cost)
                            VALUES (:budgetId, :lineNo, :processCode, :componentCode, :costNature,
                                    :bearerType, :quantity, :unitPrice, :fullCost, :studioCost)
                            """)
                    .param("budgetId", record.budgetBusinessId())
                    .param("lineNo", line.lineNo())
                    .param("processCode", line.processCode())
                    .param("componentCode", line.componentCode())
                    .param("costNature", line.costNature())
                    .param("bearerType", line.bearerType())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPrice())
                    .param("fullCost", line.fullResourceCost())
                    .param("studioCost", line.studioBorneCost())
                    .update();
        }
    }

    private record HeadRecord(
            String budgetBusinessId,
            String schemeBusinessId,
            long productContentVersion,
            long schemeContentVersion,
            String formulaRuleVersion,
            String roundingVersion,
            String inputFingerprint,
            java.math.BigDecimal fullResourceUnitCost,
            java.math.BigDecimal studioBorneUnitCost,
            java.math.BigDecimal customerSuppliedUnitValue,
            java.math.BigDecimal expectedUnitRevenue,
            java.math.BigDecimal expectedUnitProfit,
            java.math.BigDecimal expectedProfitRate) {
    }
}
