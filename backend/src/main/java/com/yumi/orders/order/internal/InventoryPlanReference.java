package com.yumi.orders.order.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿库存计划的确认时重验与展示（任务 4.8）：只读库存批次当前数量与工序状态。
 * 与 {@link OrderReference} 同风格：跨模块只读展示/校验字段，不依赖库存模块的 Java 类型
 * （否则会与「库存向订单履约台账登记事实」形成模块循环），也不复制库存业务规则。
 */
@Component
public class InventoryPlanReference {

    /** 只读批次快照：缺口判定只用 quantity，工序与缝边状态供计划行展示。 */
    public record BatchAvailability(long batchId, String batchNo, String node, String seamState, int quantity) {
    }

    private static final String COLUMNS = "id, batch_no, node, seam_state, quantity";

    private final JdbcTemplate jdbcTemplate;

    public InventoryPlanReference(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BatchAvailability batch(long batchId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM inventory_batches WHERE id = ?",
                rs -> rs.next() ? map(rs) : null, batchId);
    }

    /** 批量读取（计划行展示用）；批次已不存在时该 id 不出现在结果里。 */
    public Map<Long, BatchAvailability> batches(Collection<Long> batchIds) {
        var found = new LinkedHashMap<Long, BatchAvailability>();
        if (batchIds.isEmpty()) {
            return found;
        }
        var placeholders = String.join(",", Collections.nCopies(batchIds.size(), "?"));
        jdbcTemplate.query("SELECT " + COLUMNS + " FROM inventory_batches WHERE id IN (" + placeholders + ")",
                rs -> {
                    var batch = map(rs);
                    found.put(batch.batchId(), batch);
                }, batchIds.toArray());
        return found;
    }

    /**
     * 缺口：按批次聚合后的计划量超过该批次当前数量（批次不存在按可用 0 计）。
     * 必须按批次聚合后再比较：同一批次可被多条明细计划，逐行比较会漏判「单行都不超、合计超支」。
     */
    public List<String> gaps(Map<Long, Integer> plannedByBatch) {
        var gaps = new ArrayList<String>();
        for (var entry : plannedByBatch.entrySet()) {
            var batch = batch(entry.getKey());
            int available = batch == null ? 0 : batch.quantity();
            if (available < entry.getValue()) {
                gaps.add("批次 " + (batch == null ? entry.getKey() : batch.batchNo()) + " 可用 " + available
                        + "，计划 " + entry.getValue());
            }
        }
        return gaps;
    }

    private static BatchAvailability map(ResultSet rs) throws SQLException {
        return new BatchAvailability(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5));
    }
}
