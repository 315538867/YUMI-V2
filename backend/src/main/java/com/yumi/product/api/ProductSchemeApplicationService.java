package com.yumi.product.api;

import java.util.List;

/**
 * 方案命令。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §5、§12、§13：
 * <ul>
 *   <li>内置标准方案由商品自动维护，不可独立编辑或删除；</li>
 *   <li>自定义方案只允许覆盖非人工费用与供料方，短流程只改方案范围；</li>
 *   <li>方案与预算结果同事务提交，预算失败整体回滚。</li>
 * </ul>
 */
public interface ProductSchemeApplicationService {

    SchemeRef createCustomScheme(CreateCustomSchemeCommand command);

    SchemeRef updateCustomScheme(UpdateCustomSchemeCommand command);

    void deactivateCustomScheme(String schemeBusinessId, long expectedRowVersion);

    List<SchemeView> listSchemes(String productBusinessId);

    /** 按当前表单只读试算自定义方案预算；不写库。 */
    SchemeView.SchemeBudgetView previewBudget(
            String productBusinessId, CreateCustomSchemeCommand command);
}
