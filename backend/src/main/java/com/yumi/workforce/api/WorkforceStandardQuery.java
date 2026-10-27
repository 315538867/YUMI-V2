package com.yumi.workforce.api;

/** 标准只读查询。 */
public interface WorkforceStandardQuery {

    WorkforceStandardView getStandards();

    WorkforceStandardVersionView getStandardHistory(String standardType, String standardBusinessId);
}
