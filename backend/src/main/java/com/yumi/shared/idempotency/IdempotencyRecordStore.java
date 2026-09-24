package com.yumi.shared.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等记录存取：全局唯一幂等键 + 请求指纹，重复提交返回首次业务结果。
 */
@Component
public class IdempotencyRecordStore {

    public record Record(String key, String adminUsername, String httpMethod, String path,
                         String requestFingerprint, int responseStatus, String responseContentType,
                         byte[] responseBody) {
    }

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRecordStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Record findByKey(String key) {
        return jdbcTemplate.query("""
                        SELECT idempotency_key, admin_username, http_method, path, request_fingerprint,
                               response_status, response_content_type, response_body
                        FROM idempotency_records WHERE idempotency_key = ?
                        """,
                resultSet -> resultSet.next()
                        ? new Record(
                        resultSet.getString("idempotency_key"),
                        resultSet.getString("admin_username"),
                        resultSet.getString("http_method"),
                        resultSet.getString("path"),
                        resultSet.getString("request_fingerprint"),
                        resultSet.getInt("response_status"),
                        resultSet.getString("response_content_type"),
                        resultSet.getBytes("response_body"))
                        : null,
                key);
    }

    /** 并发同键竞争时由唯一约束兜底，调用方对 DuplicateKeyException 走重查或忽略。 */
    public void insert(Record record) {
        jdbcTemplate.update("""
                        INSERT INTO idempotency_records (
                            idempotency_key, admin_username, http_method, path, request_fingerprint,
                            response_status, response_content_type, response_body,
                            version, created_at, updated_at, request_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?)
                        """,
                record.key(), record.adminUsername(), record.httpMethod(), record.path(),
                record.requestFingerprint(), record.responseStatus(), record.responseContentType(),
                record.responseBody(), currentRequestId());
    }

    private String currentRequestId() {
        var requestId = org.slf4j.MDC.get("requestId");
        return requestId != null && requestId.length() <= 64 ? requestId : null;
    }
}
