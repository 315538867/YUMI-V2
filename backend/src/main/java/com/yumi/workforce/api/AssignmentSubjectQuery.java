package com.yumi.workforce.api;

import java.time.LocalDate;
import java.util.List;

/** 执行主体资格与当前视图查询。 */
public interface AssignmentSubjectQuery {

    AssignmentEligibility checkEligibility(
            AssignmentSubjectRef subject, String workTypeCode, LocalDate workDate);

    AssignmentSubjectCurrentView getCurrent(AssignmentSubjectRef subject);

    List<WorkforceActivityReferenceView> getActiveReferences(AssignmentSubjectRef subject);
}
