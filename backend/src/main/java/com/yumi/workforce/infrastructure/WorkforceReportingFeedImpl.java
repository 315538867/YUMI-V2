package com.yumi.workforce.infrastructure;

import com.yumi.platform.api.jdbc.JdbcTimes;
import com.yumi.workforce.api.WorkforceReportingFeed;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 人员与小组变化数据源实现。 */
public class WorkforceReportingFeedImpl implements WorkforceReportingFeed {

    private final JdbcClient jdbc;

    public WorkforceReportingFeedImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WorkforceChangePage changedSince(WorkforceChangeCursor cursor, int limit) {
        if (limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("人员变化分页大小必须在 1 到 500 之间");
        }
        java.time.Instant after = cursor == null ? java.time.Instant.EPOCH : cursor.updatedAt();
        String afterKind = cursor == null ? "" : cursor.subjectKind();
        String afterId = cursor == null ? "" : cursor.subjectBusinessId();
        List<WorkforceChangeItem> items = jdbc.sql("""
                        SELECT * FROM (
                            SELECT 'EMPLOYEE' AS subject_kind, employee_business_id AS subject_id,
                                   employee_no AS subject_no, name, employment_status AS status,
                                   content_version, updated_at
                            FROM workforce_employees
                            UNION ALL
                            SELECT 'TEAM', team_business_id, team_no, name, status, content_version,
                                   updated_at
                            FROM workforce_teams
                        ) subjects
                        WHERE updated_at > :after
                           OR (updated_at = :after AND subject_kind > :afterKind)
                           OR (updated_at = :after AND subject_kind = :afterKind
                               AND subject_id > :afterId)
                        ORDER BY updated_at, subject_kind, subject_id
                        LIMIT :limit
                        """)
                .param("after", JdbcTimes.toUtc(after))
                .param("afterKind", afterKind)
                .param("afterId", afterId)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new WorkforceChangeItem(
                        resultSet.getString("subject_kind"),
                        resultSet.getString("subject_id"),
                        resultSet.getString("subject_no"),
                        resultSet.getString("name"),
                        resultSet.getString("status"),
                        resultSet.getLong("content_version"),
                        JdbcTimes.toInstant(resultSet.getObject(
                                "updated_at", java.time.LocalDateTime.class))))
                .list();
        WorkforceChangeCursor next = items.size() < limit ? null
                : new WorkforceChangeCursor(
                        items.getLast().updatedAt(), items.getLast().subjectKind(),
                        items.getLast().subjectBusinessId());
        return new WorkforceChangePage(items, next);
    }
}
