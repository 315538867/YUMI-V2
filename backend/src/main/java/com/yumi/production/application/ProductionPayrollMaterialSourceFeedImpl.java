package com.yumi.production.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.api.ProductionPayrollMaterialSourceFeed;
import com.yumi.production.api.ProductionPayrollMaterialSourceFeed.BasisCostLine;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 工资参考的材料来源读取实现。 */
public class ProductionPayrollMaterialSourceFeedImpl
        implements ProductionPayrollMaterialSourceFeed {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ProductionPayrollMaterialSourceFeedImpl(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<MaterialItem> listForPeriod(java.time.LocalDate start, java.time.LocalDate end) {
        return jdbc.sql("""
                        SELECT m.material_source_business_id, m.task_business_id, m.demand_business_id,
                               d.order_business_id, m.component_code, m.process_code, m.quantity,
                               m.business_date, m.occurred_at, d.production_basis_json
                        FROM production_material_cost_sources m
                        JOIN production_demands d ON d.demand_business_id = m.demand_business_id
                        WHERE m.business_date BETWEEN :start AND :end
                          AND m.effective_status = 'APPLY'
                        ORDER BY m.business_date, m.occurred_at, m.material_source_business_id
                        """)
                .param("start", java.sql.Date.valueOf(start))
                .param("end", java.sql.Date.valueOf(end))
                .query((resultSet, rowNumber) -> {
                    String component = resultSet.getString("component_code");
                    String processCode = resultSet.getString("process_code");
                    JsonNode basis = readBasis(resultSet.getString("production_basis_json"));
                    JsonNode line = findCostLine(basis, component, processCode);
                    String source = line == null ? null : line.path("source").asText(null);
                    BigDecimal unit = line == null ? BigDecimal.ZERO
                            : new BigDecimal(line.path("fullResourceCost").asText("0"));
                    return new MaterialItem(
                            resultSet.getString("material_source_business_id"),
                            resultSet.getString("task_business_id"),
                            resultSet.getString("demand_business_id"),
                            resultSet.getString("order_business_id"),
                            component, source, resultSet.getInt("quantity"), unit, line != null,
                            resultSet.getObject("business_date", java.time.LocalDate.class),
                            JdbcTimes.toInstant(resultSet.getObject(
                                    "occurred_at", java.time.LocalDateTime.class)),
                            basisLines(basis));
                })
                .list();
    }

    private JsonNode readBasis(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return null;
        }
    }

    private java.util.List<BasisCostLine> basisLines(JsonNode basis) {
        if (basis == null) {
            return java.util.List.of();
        }
        java.util.List<BasisCostLine> lines = new java.util.ArrayList<>();
        for (JsonNode line : basis.path("costLines")) {
            JsonNode process = line.path("processCode");
            lines.add(new BasisCostLine(
                    line.path("componentCode").asText(),
                    process.isNull() || process.isMissingNode() ? null : process.asText(),
                    line.path("nature").asText(),
                    line.path("source").asText(),
                    new BigDecimal(line.path("fullResourceCost").asText("0"))));
        }
        return lines;
    }

    private JsonNode findCostLine(JsonNode basis, String component, String processCode) {
        if (basis == null || component == null || processCode == null) {
            return null;
        }
        for (JsonNode line : basis.path("costLines")) {
            if (!processCode.equals(line.path("processCode").asText(null))) {
                continue;
            }
            if (component.equals(line.path("componentCode").asText())
                    && "NON_LABOR_MATERIAL".equals(line.path("nature").asText())) {
                return line;
            }
        }
        return null;
    }
}
