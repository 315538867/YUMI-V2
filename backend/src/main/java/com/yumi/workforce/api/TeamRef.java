package com.yumi.workforce.api;

/** 小组引用。 */
public record TeamRef(
        String teamBusinessId, String teamNo, TeamStatus status, long contentVersion, long rowVersion) {
}
