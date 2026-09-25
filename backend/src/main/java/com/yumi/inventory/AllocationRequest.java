package com.yumi.inventory;

import java.util.List;

/**
 * 库存领用入参（任务 4.6）：一次领用一个订单，可含多条「批次 + 订单明细 + 数量 + 接入节点」。
 * 批次由管理员显式选择（推荐接口只给候选），服务端在事务内按批次 id 升序加锁后重校验余额与兼容性。
 */
public record AllocationRequest(
        Long orderId,
        String reason,
        List<Line> lines) {

    public record Line(Long batchId, Long orderItemId, Integer quantity, String targetNode) {
    }
}
