package com.yumi.production.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.api.ProductionPayrollSourceFeed;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 工资劳动与责任来源 Feed 实现。 */
public class ProductionPayrollSourceFeedImpl implements ProductionPayrollSourceFeed {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ProductionPayrollSourceFeedImpl(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public LaborPage laborAfter(LaborCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("劳动来源分页大小必须在 1 到 500 之间");
        }
        java.time.Instant after = cursor == null ? java.time.Instant.EPOCH : cursor.occurredAt();
        String afterId = cursor == null ? "" : cursor.laborSourceBusinessId();
        List<LaborItem> items = jdbc.sql("""
                        SELECT l.labor_source_business_id, l.task_business_id, l.subject_type,
                               l.subject_business_id, l.task_type, l.process_code, l.quantity,
                               l.unit_labor_amount, l.business_date, l.occurred_at,
                               l.effective_status, l.reverses_labor_source_business_id,
                               (SELECT GROUP_CONCAT(m.employee_business_id ORDER BY m.employee_business_id)
                                FROM production_assessment_member_loads m
                                JOIN production_tasks t
                                  ON t.assessment_business_id = m.assessment_business_id
                                WHERE t.task_business_id = l.task_business_id) AS participants
                        FROM production_labor_sources l
                        WHERE l.occurred_at > :after
                           OR (l.occurred_at = :after
                               AND l.labor_source_business_id > :afterId)
                        ORDER BY l.occurred_at, l.labor_source_business_id
                        LIMIT :limit
                        """)
                .param("after", JdbcTimes.toUtc(after))
                .param("afterId", afterId)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    String subjectType = resultSet.getString("subject_type");
                    String subjectId = resultSet.getString("subject_business_id");
                    String participants = resultSet.getString("participants");
                    List<String> employees = "TEAM".equals(subjectType) && participants != null
                            ? Arrays.stream(participants.split(",")).sorted().toList()
                            : List.of(subjectId);
                    BigDecimal unit = resultSet.getBigDecimal("unit_labor_amount");
                    return new LaborItem(
                            resultSet.getString("labor_source_business_id"),
                            resultSet.getString("task_business_id"),
                            subjectType, subjectId, employees,
                            resultSet.getString("task_type"),
                            resultSet.getString("process_code"),
                            (Integer) resultSet.getObject("quantity"),
                            unit,
                            resultSet.getObject("business_date", java.time.LocalDate.class),
                            JdbcTimes.toInstant(resultSet.getObject(
                                    "occurred_at", java.time.LocalDateTime.class)),
                            resultSet.getString("effective_status"),
                            resultSet.getString("reverses_labor_source_business_id"));
                })
                .list();
        LaborCursor next = items.size() < limit
                ? null : new LaborCursor(items.getLast().occurredAt(),
                        items.getLast().laborSourceBusinessId());
        return new LaborPage(items, next);
    }

    @Override
    public ResponsibilityPage responsibilityAfter(long afterSequence, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("责任来源分页大小必须在 1 到 500 之间");
        }
        List<ResponsibilityItem> items = jdbc.sql("""
                        SELECT r.feed_sequence, r.revision_business_id,
                               r.responsibility_chain_business_id,
                               r.disposition_fact_business_id, r.effect_type, r.responsibility_type,
                               r.responsible_subject_business_id, r.quantity, r.business_date,
                               r.occurred_at,
                               COALESCE(
                                   (SELECT dm.production_basis_json
                                    FROM production_disposition_facts d
                                    JOIN production_task_items ti
                                      ON ti.task_item_business_id = d.task_item_business_id
                                    JOIN production_demands dm
                                      ON dm.demand_business_id = ti.demand_business_id
                                    WHERE d.fact_business_id = r.disposition_fact_business_id
                                    LIMIT 1),
                                   (SELECT dm.production_basis_json
                                    FROM production_returned_product_disposition_facts pd
                                    JOIN production_returned_product_sources rs
                                      ON rs.returned_source_business_id = pd.returned_source_business_id
                                    JOIN production_sources ps
                                      ON ps.production_source_business_id = rs.physical_root_source_business_id
                                    JOIN production_demands dm
                                      ON dm.demand_business_id = ps.demand_business_id
                                    WHERE pd.fact_business_id = r.disposition_fact_business_id
                                    LIMIT 1)) AS basis_json
                        FROM production_responsibility_revisions r
                        WHERE r.feed_sequence > :after
                        ORDER BY feed_sequence
                        LIMIT :limit
                        """)
                .param("after", afterSequence)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    JsonNode glue = glueLine(resultSet.getString("basis_json"));
                    boolean eligible = glue != null
                            && ("EMPLOYEE".equals(resultSet.getString("responsibility_type"))
                                || "TEAM".equals(resultSet.getString("responsibility_type")))
                            && "APPLY".equals(resultSet.getString("effect_type"));
                    return new ResponsibilityItem(
                            resultSet.getLong("feed_sequence"),
                            resultSet.getString("revision_business_id"),
                            resultSet.getString("responsibility_chain_business_id"),
                            resultSet.getString("disposition_fact_business_id"),
                            resultSet.getString("effect_type"),
                            resultSet.getString("responsibility_type"),
                            resultSet.getString("responsible_subject_business_id"),
                            resultSet.getInt("quantity"),
                            eligible ? new BigDecimal(glue.path("quantity").asText("0")) : null,
                            eligible ? new BigDecimal(glue.path("unitPrice").asText("0")) : null,
                            eligible,
                            resultSet.getObject("business_date", java.time.LocalDate.class),
                            JdbcTimes.toInstant(resultSet.getObject(
                                    "occurred_at", java.time.LocalDateTime.class)));
                })
                .list();
        Long next = items.size() < limit ? null : items.getLast().feedSequence();
        return new ResponsibilityPage(items, next);
    }

    private JsonNode glueLine(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode basis = objectMapper.readTree(json);
            for (JsonNode line : basis.path("costLines")) {
                if ("GLUE".equals(line.path("componentCode").asText())
                        && "NON_LABOR_MATERIAL".equals(line.path("nature").asText())
                        && "STUDIO".equals(line.path("source").asText())) {
                    return line;
                }
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }
}
