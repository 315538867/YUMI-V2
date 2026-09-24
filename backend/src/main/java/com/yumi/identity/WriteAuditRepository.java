package com.yumi.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 写命令审计落库。独立于业务事务执行，保证失败请求仍保留安全审计。
 */
@Component
public class WriteAuditRepository {

    public record Entry(String adminUsername, String requestId, String idempotencyKey, String businessNo,
                        String httpMethod, String path, String result, int responseStatus, String errorCode) {
    }

    private final JdbcTemplate jdbcTemplate;

    public WriteAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Entry entry) {
        jdbcTemplate.update("""
                        INSERT INTO audit_logs (
                            admin_username, request_id, idempotency_key, business_no,
                            http_method, path, result, response_status, error_code, occurred_at,
                            version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """,
                entry.adminUsername(), entry.requestId(), entry.idempotencyKey(), entry.businessNo(),
                entry.httpMethod(), entry.path(), entry.result(), entry.responseStatus(), entry.errorCode());
    }
}
