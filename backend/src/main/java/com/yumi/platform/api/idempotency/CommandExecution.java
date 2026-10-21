package com.yumi.platform.api.idempotency;

/**
 * 幂等命令执行结果。
 *
 * @param outcome 业务结果引用
 * @param replayed 是否为命中既有成功记录的重放（未再次执行命令体）
 */
public record CommandExecution(CommandOutcome outcome, boolean replayed) {
}
