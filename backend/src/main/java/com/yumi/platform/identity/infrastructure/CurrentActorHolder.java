package com.yumi.platform.identity.infrastructure;

import com.yumi.platform.api.identity.CurrentActor;
import java.util.function.Supplier;

/**
 * 请求级当前操作者持有者。
 *
 * <p>由认证过滤器在请求开始时写入、结束时清除，避免线程复用导致身份泄漏。业务代码只通过
 * {@code CurrentActorProvider} 读取，不直接引用本类。
 */
public final class CurrentActorHolder {

    private static final ThreadLocal<CurrentActor> CURRENT = new ThreadLocal<>();

    /** 会话校验失败的具体原因；用于向用户返回“停用/吊销/过期”，而不是笼统的未认证。 */
    private static final ThreadLocal<com.yumi.platform.api.http.ApiException> REJECTION = new ThreadLocal<>();

    private CurrentActorHolder() {
    }

    public static void set(CurrentActor actor) {
        CURRENT.set(actor);
    }

    public static CurrentActor get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
        REJECTION.remove();
    }

    static void setRejection(com.yumi.platform.api.http.ApiException rejection) {
        REJECTION.set(rejection);
    }

    static com.yumi.platform.api.http.ApiException getRejection() {
        return REJECTION.get();
    }

    /** 当前是否已绑定操作者（过滤器未拦截时用于诊断）。 */
    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    /** 在显式身份下执行，供后台任务与测试使用；执行结束一定恢复原值。 */
    public static <T> T runAs(CurrentActor actor, Supplier<T> body) {
        CurrentActor previous = CURRENT.get();
        try {
            CURRENT.set(actor);
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
