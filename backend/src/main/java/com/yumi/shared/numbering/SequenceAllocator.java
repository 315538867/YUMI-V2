package com.yumi.shared.numbering;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.util.List;

/**
 * 业务编号分配：在调用方事务内锁定 number_sequences 行并递增（database-design §12）。
 * 序列不存在时首次插入，唯一键竞争由数据库兜底；编号格式为前缀 + 5 位递增序号。
 */
@Component
public class SequenceAllocator {

    private final JdbcTemplate jdbcTemplate;

    public SequenceAllocator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long next(String sequenceKey) {
        List<Long> current = jdbcTemplate.query(
                "SELECT current_value FROM number_sequences WHERE sequence_key = ? FOR UPDATE",
                (ResultSet rs) -> rs.next() ? List.of(rs.getLong(1)) : List.of(),
                sequenceKey);
        if (current.isEmpty()) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO number_sequences (sequence_key, current_value, version, created_at, updated_at) "
                                + "VALUES (?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                        sequenceKey);
                return 1L;
            } catch (DuplicateKeyException race) {
                current = jdbcTemplate.query(
                        "SELECT current_value FROM number_sequences WHERE sequence_key = ? FOR UPDATE",
                        (ResultSet rs) -> rs.next() ? List.of(rs.getLong(1)) : List.of(),
                        sequenceKey);
            }
        }
        var value = current.get(0) + 1;
        jdbcTemplate.update(
                "UPDATE number_sequences SET current_value = ?, version = version + 1, "
                        + "updated_at = UTC_TIMESTAMP(6) WHERE sequence_key = ?",
                value, sequenceKey);
        return value;
    }

    public static String format(String prefix, long value) {
        return prefix + String.format("%05d", value);
    }
}
