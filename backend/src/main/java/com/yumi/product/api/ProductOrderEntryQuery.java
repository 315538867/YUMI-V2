package com.yumi.product.api;

/**
 * 订单录入所需的当前方案快照入口。
 *
 * <p>订单只保存快照引用；本查询不修改方案，也不把冻结逻辑暴露成可拼接的快照内容。
 */
public interface ProductOrderEntryQuery {

    ProductSnapshotService.SchemeSnapshotRefAndView getSchemeEntry(String schemeBusinessId);
}
