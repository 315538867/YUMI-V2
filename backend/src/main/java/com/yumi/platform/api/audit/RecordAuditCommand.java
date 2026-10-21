package com.yumi.platform.api.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 审计记录命令。
 *
 * @param detail 脱敏后的补充字段；禁止放入口令、令牌或完整敏感金额集合
 */
public record RecordAuditCommand(
        String requestId,
        String accountBusinessId,
        String moduleCode,
        String actionCode,
        String targetType,
        String targetBusinessId,
        String resultCode,
        Map<String, String> detail,
        Instant occurredAt) {

    public RecordAuditCommand {
        Objects.requireNonNull(requestId, "请求编号不能为空");
        Objects.requireNonNull(moduleCode, "模块代码不能为空");
        Objects.requireNonNull(actionCode, "动作代码不能为空");
        Objects.requireNonNull(resultCode, "结果代码不能为空");
        Objects.requireNonNull(occurredAt, "发生时间不能为空");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
