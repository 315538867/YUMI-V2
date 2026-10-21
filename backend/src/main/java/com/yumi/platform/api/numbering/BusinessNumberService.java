package com.yumi.platform.api.numbering;

/**
 * 人工业务编号（商品号、订单号、员工号等）。
 *
 * <p>规则来源：`docs/一期/04-公共业务与技术规则.md` §1。编号只用于展示与搜索，不作为跨模块
 * 关联主键；并发创建同类业务时编号不得重复，重试不得另造已成功的结果。
 */
public interface BusinessNumberService {

    /**
     * 取得下一个编号。
     *
     * @param sequenceCode 序列稳定代码，例如 {@code ORD-2026}
     * @param prefix 展示前缀，例如 {@code ORD-2026-}
     * @param width 流水号位数，不足补零
     */
    String nextNumber(String sequenceCode, String prefix, int width);
}
