package com.yumi.customer.application.port;

import com.yumi.platform.api.value.ContentFingerprint;
import java.util.List;
import java.util.Optional;

/** 客户快照持久化端口。 */
public interface CustomerSnapshotRepository {

    /** 按“客户 + 来源内容版本 + 覆盖指纹”查找既有快照。 */
    Optional<CustomerSnapshotRecord> find(
            String customerBusinessId, long sourceContentVersion, ContentFingerprint overrideFingerprint);

    Optional<CustomerSnapshotRecord> findByBusinessId(String snapshotBusinessId);

    void insert(CustomerSnapshotRecord record);

    /** 该客户已产生的最大快照版本；无快照时为 0。 */
    long maxSnapshotVersion(String customerBusinessId);

    List<CustomerSnapshotRecord> findAllOfCustomer(String customerBusinessId);
}
