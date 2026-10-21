package com.yumi.platform.transaction;

import java.util.function.Predicate;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Spring 持久化异常的并发冲突判定。
 *
 * <p>只把死锁、锁等待超时与悲观锁失败视为可整体重试的冲突；其他异常（约束冲突、业务拒绝、
 * 系统错误）直接向上抛出，避免用重试掩盖真实错误。
 */
public final class SpringDeadlockDetector implements Predicate<RuntimeException> {

    private static final SpringDeadlockDetector INSTANCE = new SpringDeadlockDetector();

    private SpringDeadlockDetector() {
    }

    @Override
    public boolean test(RuntimeException failure) {
        return failure instanceof DeadlockLoserDataAccessException
                || failure instanceof CannotAcquireLockException
                || failure instanceof PessimisticLockingFailureException;
    }

    public static SpringDeadlockDetector instance() {
        return INSTANCE;
    }
}
