package com.yumi.platform.api.idempotency;

import java.util.function.Supplier;

/**
 * 正式命令幂等执行。
 *
 * <p>规则来源：`docs/一期/05-跨模块接口与事务规则.md` §9。要求：
 * <ul>
 *   <li>相同幂等键与相同输入返回原结果，命令体不重复执行；</li>
 *   <li>相同幂等键与不同输入返回 {@code IDEMPOTENCY_CONFLICT}；</li>
 *   <li>成功记录与业务写入同一事务提交，失败事务不留下成功结果；</li>
 *   <li>并发请求只产生一份成功业务结果。</li>
 * </ul>
 *
 * <p>{@code body} 必须在本服务提供的同一事务内执行；本服务不提供跨请求的“先登记后执行”两步用法。
 */
public interface IdempotentCommandService {

    /**
     * 执行命令：命中成功记录时直接返回原结果，否则在同一事务内执行 {@code body} 并登记成功结果。
     */
    CommandExecution execute(CommandRequest request, Supplier<CommandOutcome> body);
}
