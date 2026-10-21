package com.yumi.platform.aftercommit.api;

/** 来源游标：由 Feed 定义并解释，不使用数据库自增主键顺序。 */
public record SourceCursor(String feedCode, long position) {
}
