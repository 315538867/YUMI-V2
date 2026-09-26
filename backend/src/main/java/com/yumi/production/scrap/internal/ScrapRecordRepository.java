package com.yumi.production.scrap.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 报废事实查询（阶段五 5.12）：只读，事实由核验事务写入，不提供编辑或删除。 */
@Repository
public class ScrapRecordRepository {

    private static final String COLUMNS = """
            id, verification_id, task_item_id, order_id, order_item_id, product_id, node, scrap_quantity,
            reason, operator_username, recorded_at
            """;

    private static final RowMapper<ScrapRecordRow> MAPPER = ScrapRecordRepository::map;

    private final JdbcTemplate jdbcTemplate;

    public ScrapRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ScrapRecordRow> findById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM scrap_records WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<ScrapRecordRow> find(Long orderItemId, String node) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM scrap_records WHERE 1=1");
        var args = new ArrayList<Object>();
        if (orderItemId != null) {
            sql.append(" AND order_item_id = ?");
            args.add(orderItemId);
        }
        if (node != null && !node.isBlank()) {
            sql.append(" AND node = ?");
            args.add(node.trim());
        }
        sql.append(" ORDER BY id DESC");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    private static ScrapRecordRow map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp recordedAt = rs.getTimestamp("recorded_at");
        return new ScrapRecordRow(rs.getLong("id"), rs.getLong("verification_id"), rs.getLong("task_item_id"),
                rs.getLong("order_id"), rs.getLong("order_item_id"), rs.getLong("product_id"),
                rs.getString("node"), rs.getInt("scrap_quantity"), rs.getString("reason"),
                rs.getString("operator_username"),
                recordedAt == null ? null : recordedAt.toLocalDateTime());
    }
}
