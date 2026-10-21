package com.yumi.production.api;

/** 缩减结果：释放的来源占用与受影响任务。 */
public record ShrinkUnverifiedResult(
        String productionSourceBusinessId,
        int releasedQuantity,
        int releasedSeconds,
        int cancelledTaskItems,
        java.util.List<String> cancelledTaskBusinessIds) {

    public ShrinkUnverifiedResult {
        cancelledTaskBusinessIds = java.util.List.copyOf(cancelledTaskBusinessIds);
    }
}
