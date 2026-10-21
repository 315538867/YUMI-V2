package com.yumi.workforce.application.port;

import com.yumi.workforce.api.TeamStatus;
import java.time.Instant;

/** 小组当前记录（模块内部类型）。 */
public record TeamRecord(
        long id,
        String teamBusinessId,
        String teamNo,
        String name,
        TeamStatus status,
        long contentVersion,
        long rowVersion,
        Instant createdAt,
        Instant updatedAt) {
}
