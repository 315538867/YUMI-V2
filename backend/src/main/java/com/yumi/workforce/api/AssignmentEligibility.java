package com.yumi.workforce.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 执行资格检查结果。
 *
 * <p>不可用时返回明确原因，调用方必须拒绝安排，不能用默认值兜底。
 *
 * @param reasons 不可用原因；可用时为空
 */
public record AssignmentEligibility(
        AssignmentSubjectRef subject,
        String workTypeCode,
        LocalDate workDate,
        boolean eligible,
        List<String> reasons) {

    public AssignmentEligibility {
        reasons = List.copyOf(reasons);
    }
}
