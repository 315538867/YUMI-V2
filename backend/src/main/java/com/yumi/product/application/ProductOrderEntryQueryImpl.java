package com.yumi.product.application;

import com.yumi.platform.api.authorization.AuthorizationService;
import com.yumi.platform.api.authorization.PermissionCodes;
import com.yumi.platform.api.authorization.ResourceScope;
import com.yumi.product.api.ProductOrderEntryQuery;
import com.yumi.product.api.ProductSnapshotService;

/** 订单录入快照查询：复用方案快照唯一键与内容版本。 */
public class ProductOrderEntryQueryImpl implements ProductOrderEntryQuery {

    private final ProductSnapshotService snapshots;
    private final AuthorizationService authorization;

    public ProductOrderEntryQueryImpl(
            ProductSnapshotService snapshots, AuthorizationService authorization) {
        this.snapshots = snapshots;
        this.authorization = authorization;
    }

    @Override
    public ProductSnapshotService.SchemeSnapshotRefAndView getSchemeEntry(String schemeBusinessId) {
        authorization.require(PermissionCodes.PRODUCT_SNAPSHOT_READ, ResourceScope.GLOBAL);
        return snapshots.acquireSchemeSnapshot(schemeBusinessId);
    }
}
