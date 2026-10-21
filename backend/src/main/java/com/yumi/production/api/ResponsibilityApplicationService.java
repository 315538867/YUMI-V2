package com.yumi.production.api;

/**
 * 报废责任（基线 11 §7.6）。
 *
 * <p>责任数量等于所关联报废明细数量，与实物去向无关；返工不生成责任。更正只替换责任主体或
 * 免除结论，不改变物理退出、处置与数量投影。
 */
public interface ResponsibilityApplicationService {

    ResponsibilityView correct(CorrectResponsibilityCommand command);

    /** 按报废处置读取唯一有效责任。 */
    ResponsibilityView findByDisposition(String dispositionFactBusinessId);
}
