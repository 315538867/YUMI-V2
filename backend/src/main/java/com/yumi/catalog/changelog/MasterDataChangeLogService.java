package com.yumi.catalog.changelog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.identity.AuditContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 基础资料变更日志（database-design §4）：追加式保存修改前后结构化快照、原因与操作人；
 * 只插入不更新，普通编辑永不覆盖历史。
 */
@Service
public class MasterDataChangeLogService {

    public record ChangeContext(AuditContext.Context audit, String adminUsername, String requestId) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MasterDataChangeLogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(String entityType, Long entityId, String businessNo,
                       Object beforeSnapshot, Object afterSnapshot, String reason,
                       String adminUsername, String requestId) {
        try {
            var beforeJson = beforeSnapshot == null ? null : objectMapper.writeValueAsString(beforeSnapshot);
            var afterJson = objectMapper.writeValueAsString(afterSnapshot);
            jdbcTemplate.update(
                    "INSERT INTO master_data_change_logs (entity_type, entity_id, business_no, "
                            + "before_json, after_json, reason, admin_username, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))",
                    entityType, entityId, businessNo, beforeJson, afterJson, reason, adminUsername, requestId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("变更快照序列化失败", impossible);
        }
    }
}
