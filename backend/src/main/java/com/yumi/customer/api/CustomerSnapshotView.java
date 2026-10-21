package com.yumi.customer.api;

/** 客户快照解析结果：冻结的客户身份与本次交付联系信息。 */
public record CustomerSnapshotView(
        CustomerSnapshotRef ref,
        String customerNo,
        String name,
        String contactName,
        String phone,
        String address) {
}
