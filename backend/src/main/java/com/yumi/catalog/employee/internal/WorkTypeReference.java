package com.yumi.catalog.employee.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工种目录只读访问（任务 2.26）：解析静态数据 `work_types` 的 code / name / active。
 * 业务侧一律按系统固定 code 引用与存储，展示使用 name；改名不影响既有引用。
 */
@Component
public class WorkTypeReference {

    public record WorkType(long id, String code, String name, boolean active) {
    }

    private final JdbcTemplate jdbcTemplate;

    public WorkTypeReference(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<WorkType> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "SELECT id, code, name, active FROM work_types WHERE code = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
                code);
    }

    /** 按 id 批量解析为 id → 工种，保持调用方顺序由调用方自行处理。 */
    public Map<Long, WorkType> byIds(List<Long> ids) {
        var result = new LinkedHashMap<Long, WorkType>();
        if (ids.isEmpty()) {
            return result;
        }
        var placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        jdbcTemplate.query(
                "SELECT id, code, name, active FROM work_types WHERE id IN (" + placeholders + ")",
                rs -> {
                    result.put(rs.getLong("id"), map(rs));
                },
                ids.toArray());
        return result;
    }

    private static WorkType map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkType(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("active"));
    }
}
