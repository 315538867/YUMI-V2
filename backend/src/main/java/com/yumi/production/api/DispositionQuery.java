package com.yumi.production.api;

import java.util.List;

/**
 * 处置追溯查询：按任务明细或生产来源读取缺陷、处置、退出与责任。
 *
 * <p>只为追溯展示，不提供“待处置”状态：核验提交时全部不合格数量已完成处置。
 */
public interface DispositionQuery {

    List<DispositionView> findByTaskItem(String taskItemBusinessId);

    List<DispositionView> findBySource(String productionSourceBusinessId);
}
