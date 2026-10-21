package com.yumi.platform.aftercommit.api;

import java.util.Map;

/**
 * 登记提交后任务。
 *
 * @param deduplicationKey 同一类型下的去重键；重复登记不产生第二条任务
 * @param payload 任务载荷，只放可重建的引用，不放业务权威数据副本
 */
public record EnqueueAfterCommitTaskCommand(
        String taskType, String deduplicationKey, Map<String, String> payload) {
}
