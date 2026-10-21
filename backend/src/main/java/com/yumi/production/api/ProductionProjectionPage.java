package com.yumi.production.api;

import java.util.List;

/**
 * 公开投影分页结果：按 {@code (updatedAt, demandBusinessId)} 键集分页，游标不跳过、不重复。
 *
 * @param nextUpdatedAt 下一页起始更新时间；{@code null} 表示已是末页
 * @param nextDemandBusinessId 下一页起始需求标识
 */
public record ProductionProjectionPage(
        List<ProductionProjectionRow> rows,
        java.time.Instant nextUpdatedAt,
        String nextDemandBusinessId) {

    public ProductionProjectionPage {
        rows = List.copyOf(rows);
    }

    /** 是否还有下一页。 */
    public boolean hasMore() {
        return nextDemandBusinessId != null;
    }
}
