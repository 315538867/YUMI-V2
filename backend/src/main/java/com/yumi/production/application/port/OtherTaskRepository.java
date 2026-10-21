package com.yumi.production.application.port;

import com.yumi.platform.api.value.CostAmount;
import java.util.Optional;

/**
 * 其他任务详情（基线 11 §6.2）：不关联生产来源，直接录入整体标准分钟。
 *
 * <p>关联商品时冻结商品当前快照与其他任务单件人工费；未关联商品时三者均为空。
 */
public interface OtherTaskRepository {

    void insert(OtherTaskRecord record);

    Optional<OtherTaskRecord> find(String taskBusinessId);

    /** 其他任务详情记录。 */
    record OtherTaskRecord(
            String taskBusinessId,
            String workContent,
            String relatedProductBusinessId,
            String productSnapshotRef,
            Integer plannedQuantity,
            CostAmount frozenUnitLaborAmount,
            String resultNote,
            long rowVersion) {
    }
}
