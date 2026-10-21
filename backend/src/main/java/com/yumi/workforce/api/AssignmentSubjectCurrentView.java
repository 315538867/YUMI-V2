package com.yumi.workforce.api;

import java.util.List;

/** 执行主体当前视图：员工含资格，小组含完整成员与效率标准。 */
public record AssignmentSubjectCurrentView(
        AssignmentSubjectRef subject,
        String name,
        EmploymentStatus employmentStatus,
        TeamStatus teamStatus,
        List<EmployeeView.QualificationView> qualifications,
        List<String> memberEmployeeBusinessIds,
        java.math.BigDecimal efficiencyFactor,
        long contentVersion) {

    public AssignmentSubjectCurrentView {
        qualifications = List.copyOf(qualifications);
        memberEmployeeBusinessIds = List.copyOf(memberEmployeeBusinessIds);
    }
}
