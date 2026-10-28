package com.yumi.reporting.application;

/** 从各业务模块公开 Feed 读取全量来源，写入指定影子版本。 */
public interface ReportingSourceReader {

    int readAllInto(String versionBusinessId);
}
