package com.yumi.customer.api;

/**
 * 客户快照：正式引用时冻结客户资料与本次收货信息。
 *
 * <p>规则来源：`docs/一期/08-客户模块.md` §5、§9：
 * <ul>
 *   <li>同一客户、同一内容版本、相同收货覆盖值只产生一个快照；</li>
 *   <li>覆盖值不修改客户默认地址；</li>
 *   <li>取得快照时客户必须为 ACTIVE；解析历史快照不受当前停用影响。</li>
 * </ul>
 */
public interface CustomerSnapshotService {

    CustomerSnapshotRef acquireCurrentCustomerSnapshot(
            String customerBusinessId, DeliveryContactOverride override);

    CustomerSnapshotView resolve(CustomerSnapshotRef ref);

    CustomerSnapshotView resolveById(String snapshotBusinessId);
}
