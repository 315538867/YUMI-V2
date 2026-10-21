package com.yumi.platform.api.audit;

import java.time.Instant;
import java.util.List;

/**
 * 审计记录。
 *
 * <p>规则来源：`docs/一期/06-平台公共能力模块.md` §5、§9、§12：审计不可编辑、不可删除；
 * 错误通过关联补充说明记录，不覆盖原日志。审计记录与业务写入同事务，业务回滚时不得留下
 * 成功审计。
 */
public interface AuditService {

    /** 追加一条审计记录；不可修改已有记录。 */
    String record(RecordAuditCommand command);

    /** 追加补充说明，引用原审计记录，不修改原记录。 */
    String appendNote(String auditBusinessId, String note, Instant occurredAt);

    /** 按目标对象检索审计记录，按发生时间倒序。 */
    List<AuditEntry> findByTarget(String targetType, String targetBusinessId, int limit);

    /** 按请求编号检索，用于问题定位。 */
    List<AuditEntry> findByRequestId(String requestId);
}
