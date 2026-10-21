package com.yumi.workforce.api;

/** 释放预留请求：只减少原预留，不创建预留、不更换主体。 */
public record ReleaseWorkforceActivityCommand(WorkforceActivitySourceRef source) {

    public ReleaseWorkforceActivityCommand {
        if (source == null) {
            throw new IllegalArgumentException("活动来源不能为空");
        }
    }
}
