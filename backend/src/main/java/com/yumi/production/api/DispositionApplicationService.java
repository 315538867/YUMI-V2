package com.yumi.production.api;

/**
 * 处置应用服务：保证每条报废处置只有一次终态退出，返工不产生责任（基线 11 §7.6、§7.7）。
 *
 * <p>核验已经写入处置与退出；本服务负责重复投递的幂等确认与追溯一致性校验，不重复扣减来源、
 * 不重复增加缺口。
 */
public interface DispositionApplicationService {

    /**
     * 确认处置已完成终态退出；已存在唯一退出时幂等返回。
     *
     * @return 该处置对应的终态退出标识
     */
    String ensureTerminalExit(String dispositionFactBusinessId);

    DispositionView trace(String taskItemBusinessId);
}
