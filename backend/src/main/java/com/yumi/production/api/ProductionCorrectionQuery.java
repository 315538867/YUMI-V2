package com.yumi.production.api;

import java.util.List;

/**
 * 更正 Feed 读取：消费者按完整命令批次重放，成功后才推进游标。
 */
public interface ProductionCorrectionQuery {

    /** 按 Feed 序号读取指定数量之后的更正项（同一命令批次可能跨页，由消费者整批判断完整性）。 */
    List<ProductionCorrectionFeedItem> listAfter(long feedSequence, int limit);

    /** 某命令批次的全部项；用于校验完整性后再重放。 */
    List<ProductionCorrectionFeedItem> itemsOf(String correctionCommandBusinessId);
}
