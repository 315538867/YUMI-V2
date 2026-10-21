package com.yumi.workforce.api;

import java.time.LocalDate;

/** 授予或撤销工种资格。 */
public record ChangeQualificationCommand(
        String employeeBusinessId, String workTypeCode, LocalDate effectiveDate) {
}
