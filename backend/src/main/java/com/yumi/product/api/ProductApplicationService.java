package com.yumi.product.api;

/**
 * 商品命令。
 *
 * <p>规则来源：`docs/一期/09-商品模块.md` §5、§10、§12、§13：
 * <ul>
 *   <li>商品保存时同步生成完整内置标准方案与预算结果；任一步失败整笔回滚；</li>
 *   <li>商品不接收目标工序；标准售价保留在商品上；</li>
 *   <li>编辑使用行版本校验，冲突明确拒绝。</li>
 * </ul>
 */
public interface ProductApplicationService {

    ProductRef create(CreateProductCommand command);

    ProductRef update(UpdateProductCommand command);

    ProductRef changeStatus(ChangeProductStatusCommand command);
}
