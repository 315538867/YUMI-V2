package com.yumi.platform.api.jdbc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * JDBC 与领域时间的转换：数据库统一保存 UTC 的 {@code DATETIME(6)}。
 *
 * <p>放在 {@code platform.api} 下，因为它是不含业务规则的共享契约：所有模块持久化时的
 * 时间转换方式必须一致，否则历史时间会在模块间漂移。
 */
public final class JdbcTimes {

    public static final ZoneOffset UTC = ZoneOffset.UTC;

    private JdbcTimes() {
    }

    public static LocalDateTime toUtc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, UTC);
    }

    public static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(UTC);
    }
}
