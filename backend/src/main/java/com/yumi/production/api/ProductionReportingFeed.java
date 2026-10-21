package com.yumi.production.api;

import java.time.Instant;

/**
 * 生产公开数量投影 Feed（报表与只读页面消费）。
 *
 * <p>按 {@code (updatedAt, demandBusinessId)} 键集分页；同一需求在多页之间可能因更新而移动，
 * 消费者以需求标识去重并取较新一版。
 */
public interface ProductionReportingFeed {

    ProductionProjectionPage listAfter(Instant updatedAfter, String afterDemandBusinessId, int limit);
}
