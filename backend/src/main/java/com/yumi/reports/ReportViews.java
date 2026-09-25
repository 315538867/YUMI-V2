package com.yumi.reports;

import java.util.List;
import java.util.Map;

/** 报表读模型与一致性检查结果（阶段九）：金额为字符串，只读来自事实/投影。 */
public final class ReportViews {

    private ReportViews() {
    }

    /** 报表页：类型、列清单、行数据与分页信息（筛选与分页均由服务端执行）。 */
    public record ReportPage(
            String type,
            String name,
            List<ReportTypes.Column> columns,
            List<Map<String, Object>> rows,
            int page,
            int size,
            long total) {
    }

    /** 一致性检查项：不一致时返回失败证据（不做静默覆盖）。 */
    public record ConsistencyCheck(String name, boolean consistent, String detail) {
    }

    public record ConsistencyReport(List<ConsistencyCheck> checks, boolean allConsistent) {
    }
}
