package com.yumi.platform.idempotency.application;

import com.yumi.platform.api.idempotency.IdempotentCommandService;

/**
 * 幂等服务的实现契约占位。
 *
 * <p>仅用于让实现类显式实现公开接口；业务模块只依赖
 * {@link com.yumi.platform.api.idempotency.IdempotentCommandService}。
 */
public interface IdempotentCommandServiceContract extends IdempotentCommandService {
}
