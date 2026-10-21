package com.yumi.production.api;

import java.util.List;

/** 缺陷原因查询：核验页面只能选启用原因。 */
public interface DefectReasonQuery {

    List<DefectReasonView> listAll();

    List<DefectReasonView> listActive();
}
