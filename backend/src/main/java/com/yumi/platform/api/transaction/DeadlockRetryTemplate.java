package com.yumi.platform.api.transaction;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 死锁与锁等待超时的整体重试模板。
 *
 * <p>规则来源：`docs/一期/05-跨模块接口与事务规则.md` §7 与 design D2：死锁只重试整个幂等命令，
 * 不局部重跑 SQL。调用方把“开始事务 → 鉴权 → 幂等 → 全部参与写入 → 提交”作为一个整体交给本模板，
 * 重试时重新读取并锁定数据，避免持有部分旧快照继续写入。
 *
 * <p>重试次数有限且带退避；超过上限后抛出最后一次失败，由适配层映射为
 * {@code CONCURRENT_CONFLICT}，不返回半成功结果。
 */
public final class DeadlockRetryTemplate {

    /** 重试次数上限。 */
    public static final int MAX_ATTEMPTS = 3;

    private final int maxAttempts;

    public DeadlockRetryTemplate() {
        this(MAX_ATTEMPTS);
    }

    public DeadlockRetryTemplate(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("重试次数必须为正数：" + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * 执行整个命令事务；捕获 {@link RetryableConflict} 后整体重来。
     *
     * @param transactionBody 一个完整事务的操作
     * @param conflictDetector 判断异常是否属于可重试的并发冲突
     */
    public <T> T execute(Supplier<T> transactionBody, java.util.function.Predicate<RuntimeException> conflictDetector) {
        Objects.requireNonNull(transactionBody, "事务体不能为空");
        Objects.requireNonNull(conflictDetector, "冲突判定不能为空");

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return transactionBody.get();
            } catch (RuntimeException failure) {
                if (!conflictDetector.test(failure)) {
                    throw failure;
                }
                lastFailure = failure;
                backoff(attempt);
            }
        }
        throw new RetryableConflict("并发冲突重试 " + maxAttempts + " 次后仍失败，请稍后重试", lastFailure);
    }

    /** 可重试的并发冲突：适配层映射为 {@code CONCURRENT_CONFLICT}。 */
    public static final class RetryableConflict extends RuntimeException {

        public RetryableConflict(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发重试被中断", interrupted);
        }
    }
}
