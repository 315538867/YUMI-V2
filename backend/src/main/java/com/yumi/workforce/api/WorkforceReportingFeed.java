package com.yumi.workforce.api;

import java.time.Instant;
import java.util.List;

/** 人员与小组变化数据源：员工、小组分别以稳定标识和内容版本发布。 */
public interface WorkforceReportingFeed {

    WorkforceChangePage changedSince(WorkforceChangeCursor cursor, int limit);

    record WorkforceChangeCursor(Instant updatedAt, String subjectKind, String subjectBusinessId) {
        public WorkforceChangeCursor {
            if (updatedAt == null || subjectKind == null || subjectBusinessId == null) {
                throw new IllegalArgumentException("人员变化游标不完整");
            }
        }
    }

    record WorkforceChangePage(List<WorkforceChangeItem> items, WorkforceChangeCursor nextCursor) {
        public WorkforceChangePage {
            items = List.copyOf(items);
        }
    }

    record WorkforceChangeItem(
            String subjectKind, String subjectBusinessId, String subjectNo, String name,
            String status, long contentVersion, Instant updatedAt) {
    }
}
