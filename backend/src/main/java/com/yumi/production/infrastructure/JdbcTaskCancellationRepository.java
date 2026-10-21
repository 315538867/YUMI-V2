package com.yumi.production.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.production.application.port.TaskCancellationRepository;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 取消事实持久化（MySQL）。 */
@Repository
public class JdbcTaskCancellationRepository implements TaskCancellationRepository {

    private final JdbcClient jdbc;

    public JdbcTaskCancellationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCancellation(
            String taskBusinessId, String reasonCode, String reasonText, Instant occurredAt, String operatorId) {
        jdbc.sql("""
                        INSERT INTO production_task_cancellation_facts (
                            fact_business_id, task_business_id, reason_code, reason_text, occurred_at,
                            operator_id, is_valid)
                        VALUES (:factId, :taskId, :reasonCode, :reasonText, :now, :operatorId, 1)
                        """)
                .param("factId", "TCF-" + java.util.UUID.randomUUID())
                .param("taskId", taskBusinessId)
                .param("reasonCode", reasonCode)
                .param("reasonText", reasonText)
                .param("now", JdbcTimes.toUtc(occurredAt))
                .param("operatorId", operatorId)
                .update();
    }
}
