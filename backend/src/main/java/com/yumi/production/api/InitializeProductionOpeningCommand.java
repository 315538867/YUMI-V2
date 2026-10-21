package com.yumi.production.api;

import java.util.List;

/**
 * 一次性期初初始化命令：状态明细合计必须等于来源接管数量。
 */
public record InitializeProductionOpeningCommand(
        String demandBusinessId, List<ProductionOpeningLine> lines) {

    public InitializeProductionOpeningCommand {
        if (demandBusinessId == null || demandBusinessId.isBlank()) {
            throw new IllegalArgumentException("期初初始化必须指定生产需求");
        }
        lines = List.copyOf(lines == null ? List.of() : lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("期初初始化必须给出状态明细");
        }
    }
}
