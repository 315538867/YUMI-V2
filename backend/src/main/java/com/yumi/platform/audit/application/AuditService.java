package com.yumi.platform.audit.application;

import com.yumi.platform.api.audit.AuditEntry;
import com.yumi.platform.api.audit.AuditServiceContract;
import com.yumi.platform.api.audit.RecordAuditCommand;
import com.yumi.platform.api.http.ApiErrorType;
import com.yumi.platform.api.http.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计服务。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9、§12：审计与业务写入同事务；
 * 业务回滚时不得留下成功审计；审计不可编辑或删除，只能追加关联补充说明。
 *
 * <p>敏感字段脱敏：明细中禁止出现口令、令牌与完整敏感金额集合；本服务对已知敏感键做遮蔽，
 * 且不记录请求体原文。
 */
public class AuditService implements AuditServiceContract {

    /** 需要脱敏的明细键（小写比较）。 */
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i).*(password|token|secret|authorization|credential).*");

    private static final String REDACTED = "***";

    private final JdbcClient jdbc;

    private final ObjectMapper objectMapper;

    public AuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String record(RecordAuditCommand command) {
        Objects.requireNonNull(command, "审计命令不能为空");
        String auditBusinessId = "AUD-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO platform_audit_logs (
                            audit_business_id, request_id, account_business_id, module_code, action_code,
                            target_type, target_business_id, result_code, note_of_audit_business_id,
                            detail_json, occurred_at)
                        VALUES (:auditBusinessId, :requestId, :accountBusinessId, :moduleCode,
                                :actionCode, :targetType, :targetBusinessId, :resultCode, NULL,
                                :detailJson, :occurredAt)
                        """)
                .param("auditBusinessId", auditBusinessId)
                .param("requestId", command.requestId())
                .param("accountBusinessId", command.accountBusinessId())
                .param("moduleCode", command.moduleCode())
                .param("actionCode", command.actionCode())
                .param("targetType", command.targetType())
                .param("targetBusinessId", command.targetBusinessId())
                .param("resultCode", command.resultCode())
                .param("detailJson", toJson(mask(command.detail())))
                .param("occurredAt",
                        java.time.LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC))
                .update();
        return auditBusinessId;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String appendNote(String auditBusinessId, String note, Instant occurredAt) {
        if (auditBusinessId == null || auditBusinessId.isBlank()) {
            throw new IllegalArgumentException("被补充的审计记录标识不能为空");
        }
        AuditEntry original = findOne(auditBusinessId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorType.NOT_FOUND, "AUDIT_NOT_FOUND", "审计记录不存在"));
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("补充说明不能为空");
        }
        String noteId = "AUD-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO platform_audit_logs (
                            audit_business_id, request_id, account_business_id, module_code, action_code,
                            target_type, target_business_id, result_code, note_of_audit_business_id,
                            detail_json, occurred_at)
                        VALUES (:auditBusinessId, :requestId, :accountBusinessId, :moduleCode,
                                :actionCode, :targetType, :targetBusinessId, 'NOTE',
                                :noteOf, :detailJson, :occurredAt)
                        """)
                .param("auditBusinessId", noteId)
                .param("requestId", original.requestId())
                .param("accountBusinessId", original.accountBusinessId())
                .param("moduleCode", original.moduleCode())
                .param("actionCode", original.actionCode())
                .param("targetType", original.targetType())
                .param("targetBusinessId", original.targetBusinessId())
                .param("noteOf", auditBusinessId)
                .param("detailJson", toJson(Map.of("note", note)))
                .param("occurredAt", java.time.LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .update();
        return noteId;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> findByTarget(String targetType, String targetBusinessId, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE target_type = :targetType AND target_business_id = :targetBusinessId
                         ORDER BY occurred_at DESC, id DESC LIMIT :limit
                        """)
                .param("targetType", targetType)
                .param("targetBusinessId", targetBusinessId)
                .param("limit", Math.max(1, limit))
                .query(this::map)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> findByRequestId(String requestId) {
        return jdbc.sql(SELECT + " WHERE request_id = :requestId ORDER BY occurred_at, id")
                .param("requestId", requestId)
                .query(this::map)
                .list();
    }

    private java.util.Optional<AuditEntry> findOne(String auditBusinessId) {
        return jdbc.sql(SELECT + " WHERE audit_business_id = :auditBusinessId")
                .param("auditBusinessId", auditBusinessId)
                .query(this::map)
                .optional();
    }

    private static final String SELECT = """
            SELECT audit_business_id, request_id, account_business_id, module_code, action_code,
                   target_type, target_business_id, result_code, note_of_audit_business_id,
                   detail_json, occurred_at
            FROM platform_audit_logs
            """;

    private AuditEntry map(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new AuditEntry(
                resultSet.getString("audit_business_id"),
                resultSet.getString("request_id"),
                resultSet.getString("account_business_id"),
                resultSet.getString("module_code"),
                resultSet.getString("action_code"),
                resultSet.getString("target_type"),
                resultSet.getString("target_business_id"),
                resultSet.getString("result_code"),
                resultSet.getString("note_of_audit_business_id"),
                fromJson(resultSet.getString("detail_json")),
                com.yumi.platform.api.jdbc.JdbcTimes.toInstant(
                        resultSet.getObject("occurred_at", java.time.LocalDateTime.class)));
    }

    private Map<String, String> mask(Map<String, String> detail) {
        if (detail == null || detail.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> masked = new java.util.LinkedHashMap<>();
        detail.forEach((key, value) ->
                masked.put(key, SENSITIVE_KEY.matcher(key).matches() ? REDACTED : value));
        return masked;
    }

    private String toJson(Map<String, String> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计明细无法序列化", exception);
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(java.util.LinkedHashMap.class, String.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计明细无法解析", exception);
        }
    }
}
