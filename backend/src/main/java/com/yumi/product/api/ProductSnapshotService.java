package com.yumi.product.api;

/**
 * 商品与方案快照：按内容版本冻结，唯一键防止并发重复创建。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §9.5、§12：
 * <ul>
 *   <li>相同内容版本重复取得返回同一快照，不重复生成；</li>
 *   <li>定义变更只产生新版本，不改旧快照；</li>
 *   <li>解析历史快照不受当前停用状态影响。</li>
 * </ul>
 */
public interface ProductSnapshotService {

    ProductSnapshotRef acquireProductSnapshot(String productBusinessId);

    SchemeSnapshotRefAndView acquireSchemeSnapshot(String schemeBusinessId);

    ProductSnapshotView resolveProduct(ProductSnapshotRef ref);

    ProductSnapshotView resolveProductById(String snapshotBusinessId);

    SchemeSnapshotView resolveScheme(ProductSnapshotRef ref);

    /** 方案快照引用与其冻结视图，避免调用方二次查询。 */
    record SchemeSnapshotRefAndView(ProductSnapshotRef ref, SchemeSnapshotView view) {
    }
}
